# Veřejné datasety

Ověřeno 28. 9. 2026 (z README na GitHubu a z popisu na figshare / Mendeley).

## Přehled

| | **RDD2022** | **RSCD** |
|---|---|---|
| Úloha | detekce poškození silnic (boxy) | klasifikace povrchu (celý snímek) |
| Autoři | Arya a kol., University of Tokyo (Sekimoto Lab) | Zhao a kol., Tsinghua University |
| Velikost | 47 420 snímků, > 55 000 objektů; 12,4 GB celkem | ~1 mil. výřezů 240×360 px (train ~960 k / val ~20 k / test ~50 k) |
| Třídy | D00 podélná trhlina, D10 příčná trhlina, D20 síťové trhliny, D40 díra (výtluk) | 27 tříd = tření (dry, wet, water, fresh_snow, melted_snow, ice) × materiál (asphalt, concrete, mud, gravel) × nerovnost (smooth, slight, severe) |
| Formát anotací | Pascal VOC `.xml` (jen trénovací část, test je bez anotací) | složka = třída (train), třída v názvu souboru (test) |
| Licence | **CC BY-SA 4.0** podle README na GitHubu; na figshare uvedeno **CC BY 4.0** | **CC BY-NC** (nekomerční použití) |
| Zdroj | [github.com/sekilab/RoadDamageDetector](https://github.com/sekilab/RoadDamageDetector), [figshare 10.6084/m9.figshare.21431547](https://figshare.com/articles/dataset/RDD2022_-_The_multi-national_Road_Damage_Dataset_released_through_CRDDC_2022/21431547) | [thu-rsxd.com/rscd](https://thu-rsxd.com/rscd/), [Mendeley Data](https://data.mendeley.com/datasets/w86hvkrzc5/3), [figshare](https://figshare.com/articles/dataset/Road_Surface_Image_Dataset_with_Detailed_Annotations_for_Driving_Assistance/20424582) |
| Citace | Arya D. a kol.: *RDD2022: A multi-national image dataset for automatic road damage detection*, Geoscience Data Journal, 2024 | Zhao T. a kol.: *A road surface image dataset with detailed annotations for driving assistance applications*, Data in Brief, 2022 |

**Co z licencí plyne pro ročníkovou práci:** obě licence dovolují použití ve školní
práci. Je potřeba **uvést autory a licenci** (citace výše patří do seznamu literatury).
CC BY-NC u RSCD znamená, že model natrénovaný na RSCD by se neměl komerčně prodávat.
Rozpor CC BY vs. CC BY-SA u RDD2022 v práci zmiň a řiď se přísnější variantou
(CC BY-SA = případné sdílení odvozených dat pod stejnou licencí). Samotné snímky
datasetů do repozitáře necommitujeme (jsou v `.gitignore`).

## RDD2022 – co stáhnout

Archivy po zemích (velikosti z README):

| Země | Velikost | Poznámka |
|---|---|---|
| **Czech** | 245 MB | ✅ české silnice – nejdůležitější pro nás |
| **China_MotorBike** | 183 MB | ✅ fotoaparát na **motorce** – podobný pohled jako naše kamera |
| Japan | 1 023 MB | ✅ nejvíc anotací |
| India | 502 MB | ✅ hodně děr (D40) |
| United_States | 424 MB | ✅ |
| China_Drone | 153 MB | ❌ pohled shora z dronu – jiný úhel |
| Norway | 9,9 GB | ❌ velké fotky z auta (> 5 GB – jen po dohodě) |

Výchozí výběr v `configs/config.yaml` je prvních pět zemí, **celkem ~2,4 GB**.

**Mapování tříd** (config `rdd2022.class_map`): `D40 → díra`, `D00/D10/D20 → trhlina`.
Ostatní štítky, které se v některých zemích vyskytují (D01, D11, D43, D44, D50, Repair …),
se ignorují a skript vypíše jejich počet. Proč trhliny jako samostatná třída, a ne jen
díry: detektor se tak učí je od děr odlišit (méně falešných poplachů) a v overlayi je
můžeme zobrazit jako méně závažné (oranžový rámeček).

## RSCD – jak stáhnout

Oficiální odkazy (Google Drive / Baidu) jsou na [thu-rsxd.com/rscd](https://thu-rsxd.com/rscd/);
kopie jsou i na Mendeley Data a figshare. Stáhni archiv (nebo zkopíruj odkaz) a spusť:

```bash
python -m src.dataset.download rscd --url "<odkaz na Google Drive>"
# nebo
python -m src.dataset.download rscd --archive ~/Downloads/RSCD.zip
```

> ⚠️ Celý RSCD je velký. Pokud web nabízí jen celý archiv a má > 5 GB, ozvi se –
> na trénink stačí podmnožina (config `rscd.max_per_class: 6000` → ~30 000 snímků).

**Mapování 27 tříd → našich 5** (`src/dataset/rscd_to_classes.py`):

| RSCD | naše třída |
|---|---|
| dry + asphalt/concrete + smooth (a slight*) | asfalt |
| dry + asphalt/concrete + severe (a slight*) | rozbitý asfalt |
| cokoli + gravel | štěrk |
| cokoli + mud | hlína / bláto |
| wet/water + asphalt/concrete | mokro |
| fresh_snow, melted_snow, ice | *nepoužito* (motorka v zimě nejezdí) |

\* „slight“ (mírně nerovný) patří podle `rscd.slight_is_damaged` buď k asfaltu (výchozí),
nebo k rozbitému asfaltu. Rozhodnutí můžeš ověřit v experimentech.

**Pozor na rozdělení dat:** RSCD vzniklo z videí, takže sousední snímky jsou skoro
stejné. Skript proto zachovává **původní rozdělení** train/val/test od autorů, když ho
ve složkách najde – náhodné dělení by přesnost uměle zvýšilo.

## Známá omezení (do diskuse v práci)

* RSCD jsou **výřezy povrchu** (bez okolí), kdežto kamera na řídítkách vidí celou scénu.
  Proto se v modelu povrchu bude klasifikovat **výřez spodní části snímku** (fáze 2).
* RDD2022 je focené z auta/motorky v jiných zemích; české silnice jsou jen v části
  „Czech“ – proto doladění na vlastních záběrech (fáze 6).
* RSCD i RDD2022 obsahují převážně denní snímky – noc bude slabé místo (analýza chyb).
