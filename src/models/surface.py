"""Model povrchu silnice: transfer learning na lehké síti (MobileNetV3 / EfficientNet-B0).

- Síť předtrénovaná na ImageNetu, vyměníme jen poslední vrstvu (5 tříd povrchu).
- Normalizace (ImageNet mean/std) je UVNITŘ modelu -> vstup je obyčejné RGB 0–1.
  Díky tomu telefon nemusí nic přepočítávat a export do TFLite je jednodušší.
- Na vlastních záběrech (celý snímek) se klasifikuje výřez spodní části obrazu = silnice
  těsně před motorkou; stejný výřez dělá aplikace (Models.java).
"""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np
import torch
from torch import nn
from torch.utils.data import Dataset

from src.models.augment import RoadAugment

ARCHS = ("mobilenet_v3_small", "mobilenet_v3_large", "efficientnet_b0")
IMAGENET_MEAN = (0.485, 0.456, 0.406)
IMAGENET_STD = (0.229, 0.224, 0.225)
IMAGE_EXT = {".jpg", ".jpeg", ".png"}

# výřez silnice (poměr k šířce / výšce snímku) – shodně s aplikací
ROAD_CROP = (0.2, 0.55, 0.8, 1.0)


def road_crop(img: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    x1, y1, x2, y2 = ROAD_CROP
    return img[int(h * y1):int(h * y2), int(w * x1):int(w * x2)]


class SurfaceNet(nn.Module):
    """Páteřní síť + nová klasifikační vrstva; vstup RGB 0–1 (N, 3, H, W), výstup logity."""

    def __init__(self, arch: str = "mobilenet_v3_small", num_classes: int = 5, pretrained: bool = True):
        super().__init__()
        import torchvision.models as tvm

        if arch not in ARCHS:
            raise ValueError(f"Neznámá architektura {arch}, vyber z {ARCHS}")
        weights = "DEFAULT" if pretrained else None
        net = getattr(tvm, arch)(weights=weights)
        last = net.classifier[-1]
        net.classifier[-1] = nn.Linear(last.in_features, num_classes)
        if not pretrained:
            # torchvision má u MobileNetV3 momentum BatchNormu 0,01 (vhodné pro dlouhý trénink
            # z ImageNetu); při učení od nuly by se průběžné statistiky nestihly ustálit
            for m in net.modules():
                if isinstance(m, nn.BatchNorm2d):
                    m.momentum = 0.1
        self.arch = arch
        self.backbone = net
        self.register_buffer("mean", torch.tensor(IMAGENET_MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(IMAGENET_STD).view(1, 3, 1, 1))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.backbone((x - self.mean) / self.std)

    def head_parameters(self):
        return self.backbone.classifier.parameters()

    def freeze_backbone(self, frozen: bool) -> None:
        """Nejdřív trénujeme jen hlavu (rychlé přizpůsobení), pak celou síť."""
        for p in self.backbone.features.parameters():
            p.requires_grad = not frozen


class ProbsWrapper(nn.Module):
    """Pro export: výstup = pravděpodobnosti (softmax)."""

    def __init__(self, model: SurfaceNet):
        super().__init__()
        self.model = model

    def forward(self, x):
        return torch.softmax(self.model(x), dim=1)


def list_images(roots: list[Path], split: str, classes: list[str]) -> list[tuple[Path, int, bool]]:
    """Najde snímky ve struktuře <root>/<split>/<třída>/*.jpg.
    Třetí hodnota = jde o celý snímek z kamery (custom_*) -> dělat výřez silnice."""
    items = []
    for root in roots:
        full_frame = "custom" in root.name
        for ci, c in enumerate(classes):
            d = root / split / c
            if d.exists():
                items += [(p, ci, full_frame) for p in sorted(d.iterdir()) if p.suffix.lower() in IMAGE_EXT]
    return items


class SurfaceDataset(Dataset):
    def __init__(self, items, img_size: int = 224, train: bool = False, seed: int | None = None):
        self.items = items
        self.size = img_size
        self.train = train
        self.aug = RoadAugment(seed=seed) if train else None

    def __len__(self):
        return len(self.items)

    def load(self, i: int) -> np.ndarray:
        path, _, full = self.items[i]
        img = cv2.cvtColor(cv2.imread(str(path)), cv2.COLOR_BGR2RGB)
        return road_crop(img) if full else img

    def __getitem__(self, i):
        img = self.load(i)
        if self.train:
            # náhodný výřez 70–100 % (jiná vzdálenost / šířka silnice)
            h, w = img.shape[:2]
            s = np.random.uniform(0.7, 1.0)
            ch, cw = int(h * s), int(w * s)
            y0, x0 = np.random.randint(0, h - ch + 1), np.random.randint(0, w - cw + 1)
            img = img[y0:y0 + ch, x0:x0 + cw]
            img = self.aug(img)
        img = cv2.resize(img, (self.size, self.size), interpolation=cv2.INTER_AREA)
        x = torch.from_numpy(np.ascontiguousarray(img)).permute(2, 0, 1).float() / 255
        return x, self.items[i][1]


def save_checkpoint(model: SurfaceNet, path: Path, classes: list[str], img_size: int, extra: dict | None = None):
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"state_dict": model.state_dict(), "arch": model.arch, "classes": classes,
                "img_size": img_size, **(extra or {})}, path)


def load_checkpoint(path: str | Path, device: str = "cpu") -> tuple[SurfaceNet, dict]:
    ck = torch.load(path, map_location=device, weights_only=False)
    model = SurfaceNet(ck["arch"], len(ck["classes"]), pretrained=False)
    model.load_state_dict(ck["state_dict"])
    return model.to(device).eval(), ck


@torch.no_grad()
def predict(model: SurfaceNet, img_rgb: np.ndarray, img_size: int = 224, full_frame: bool = True,
            device: str = "cpu") -> np.ndarray:
    """Pravděpodobnosti tříd pro jeden snímek (RGB uint8)."""
    if full_frame:
        img_rgb = road_crop(img_rgb)
    img = cv2.resize(img_rgb, (img_size, img_size), interpolation=cv2.INTER_AREA)
    x = torch.from_numpy(img).permute(2, 0, 1).float().unsqueeze(0).to(device) / 255
    return torch.softmax(model(x), 1)[0].cpu().numpy()
