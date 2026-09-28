# MotorCam 🏍️

Ročníková práce: systém, který z kamery na řídítkách motorky rozpozná **povrch silnice**
(asfalt / rozbitý asfalt / štěrk / hlína-bláto / mokro), **detekuje díry a trhliny**
a podle toho – spolu s rychlostí z GPS a zatáčkami z OpenStreetMap – rozsvítí
**varovnou kontrolku** (zelená / oranžová / červená).

> ⚠️ Prototyp vyhodnocovaný na nahraných jízdách, **ne bezpečnostní systém pro reálný provoz**.

Vše je zdarma: Python, PyTorch, ultralytics, OpenStreetMap, trénink na Google Colab / Kaggle.

## Stav

| Fáze | Stav |
|---|---|
| 1. Data | ✅ hotovo – ke kontrole |
| 2. Model povrchu | ⏳ |
| 3. Model děr | ⏳ |
| 4. Rozhodovací logika + zatáčky | ⏳ |
| 5. Demo + TFLite | ⏳ |
| 6. Experimenty | ⏳ |

Podrobný plán: [docs/PLAN.md](docs/PLAN.md)

## Struktura repozitáře

```
configs/config.yaml        všechna nastavení (cesty, třídy, prahy, parametry zatáček)
data/
  raw/                     stažené archivy (necommitují se)
  processed/               data připravená pro trénink (necommitují se)
  custom/                  vlastní videa, CSV senzorů, snímky, exporty anotací
docs/
  PLAN.md                  plán a architektura
  datasety.md              datasety, licence, mapování tříd
  anotace.md               natáčení, výběr snímků, anotace v Label Studiu / CVAT
  label_studio_config.xml  šablona anotačního projektu
notebooks/                 Colab notebooky (01_data, 02_surface, …)
src/
  dataset/                 stažení, převody, snímky z videa, import anotací, statistiky
  models/                  model povrchu a detektor (fáze 2–3)
  fusion/                  rozhodovací logika, zatáčky (fáze 4)
  demo/                    výstupní video, export TFLite (fáze 5)
  utils/                   konfigurace, jednotný styl grafů
models/                    natrénované váhy (necommitují se)
results/figures, tables/   grafy a tabulky do práce
tests/                     testy (pytest)
```

## Instalace

```bash
git clone <url repozitáře> motorcam && cd motorcam
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python -m pytest            # ověření, že vše funguje
```

Na Colabu stačí otevřít `notebooks/01_data.ipynb` – instalaci udělá sám.

## Fáze 1 – Data

Všechny příkazy se spouští z kořene repozitáře.

```bash
# 1) RDD2022 – díry a trhliny (výchozí země ≈ 2,4 GB; > 5 GB se skript zeptá)
python -m src.dataset.download rdd2022
python -m src.dataset.rdd_to_yolo                 # -> data/processed/rdd2022_yolo/data.yaml

# 2) RSCD – povrchy (odkaz z https://thu-rsxd.com/rscd/)
python -m src.dataset.download rscd --url "<odkaz>"
python -m src.dataset.rscd_to_classes             # -> data/processed/rscd_cls/{train,val,test}/<třída>/

# 3) Vlastní videa -> snímky k anotaci (2 FPS, bez rozmazaných a duplicit)
python -m src.dataset.extract_frames data/custom/videos/

# 4) Po anotaci v Label Studiu (viz docs/anotace.md)
python -m src.dataset.import_annotations labelstudio data/custom/labelstudio_export.json

# 5) Grafy a tabulky se složením dat -> results/
python -m src.dataset.dataset_stats
```

| Skript | Co dělá |
|---|---|
| `download.py` | stáhne RDD2022 po zemích (navazuje přerušené stahování), RSCD z odkazu/archivu |
| `rdd_to_yolo.py` | Pascal VOC → YOLO, mapování D40→díra, D00/D10/D20→trhlina, train/val po zemích |
| `rscd_to_classes.py` | 27 tříd RSCD → 5 tříd, vyvážený výběr, zachová původní rozdělení autorů |
| `extract_frames.py` | snímky z videa, filtr rozmazání (Laplacián) a duplicit (dHash), `frames.csv` s časy |
| `import_annotations.py` | Label Studio JSON / YOLO z CVAT → YOLO + ImageFolder + metadata; dělení po videích |
| `dataset_stats.py` | grafy a CSV tabulky počtů do `results/` |

## Datasety a licence

* **RDD2022** – Arya a kol., 2024, CC BY-SA 4.0 (figshare uvádí CC BY 4.0)
* **RSCD** – Zhao a kol., 2022, CC BY-NC

Podrobnosti a citace: [docs/datasety.md](docs/datasety.md).
