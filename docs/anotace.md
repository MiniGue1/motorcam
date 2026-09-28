# Vlastní data: natáčení, výběr snímků a anotace

## 1. Natáčení

* **Uchycení:** mobil/akční kamera na řídítkách, objektiv mírně dolů – silnice by
  měla zabírat aspoň spodní polovinu obrazu. Kamera se nesmí během jízdy posouvat.
* **Nastavení:** 1080p, 30 FPS, stabilizace zapnutá, pevná orientace na šířku.
* **Senzory (volitelně):** zdarma aplikace [Sensor Logger](https://www.tszheichoi.com/sensorlogger)
  (Android i iOS) – zapni *Location*, *Accelerometer*, *Gyroscope*, export **CSV**.
  Záznam spusť těsně před videem a na začátku jízdy **ťukni do mobilu/řídítek** –
  otřes je vidět v akcelerometru i ve videu a podle něj se data přesně spárují.
* **Pestrost:** různé povrchy (asfalt, rozbitý asfalt, šotolina, polní cesta, mokro),
  denní doby (i šero/noc), slunce se stíny stromů. Pro poctivé testování natoč
  **aspoň 4–5 samostatných jízd** – celé jízdy pak půjdou do testovací části.
* Pojmenuj videa bez mezer a diakritiky: `2026-10-03_jizda1.mp4`.

Videa ukládej do `data/custom/videos/`, CSV ze senzorů do `data/custom/sensors/`
se stejným názvem (`2026-10-03_jizda1.csv`).

## 2. Výběr snímků

```bash
python -m src.dataset.extract_frames data/custom/videos/ --fps 2
```

* 2 FPS = při 50 km/h snímek každých ~7 m, sousední snímky se tolik neopakují.
* Rozmazané snímky (otřesy) a duplicitní (stání na křižovatce) se přeskočí.
  Pokud jich přeskočí moc, sniž `--blur` (např. 30).
* `data/custom/frames/frames.csv` obsahuje čas každého snímku ve videu – podle něj
  později snímek spárujeme s GPS rychlostí.
* Kolik anotovat: **300–600 snímků** je realistické (≈ 3–5 h práce) a na doladění
  stačí. Přednostně snímky s dírami a s méně častými povrchy.

## 3. Nástroj: Label Studio (doporučeno) vs. CVAT

| | **Label Studio** | CVAT |
|---|---|---|
| Instalace | `pip install label-studio` – běží lokálně | Docker nebo cloud app.cvat.ai (free tier) |
| Boxy + štítek celého snímku v jednom průchodu | ✅ | ⚠️ tagy se neexportují do YOLO |
| Export | JSON (vše), YOLO (jen boxy) | YOLO, COCO, … |
| Poloautomatická anotace modelem | přes ML backend (složitější) | ✅ jednodušší |

**Doporučení:** Label Studio – jeden průchod snímkem = boxy + povrch + podmínky
(stín, noc …), které pak využijeme v analýze chyb.

### Postup v Label Studiu

```bash
pip install label-studio
# zpřístupnění snímků z disku (jinak se musí nahrávat přes prohlížeč)
export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=$(pwd)/data/custom
label-studio start          # otevře http://localhost:8080
```

1. **Create Project** → název „MotorCam“.
2. **Labeling Setup → Custom template → Code:** vlož obsah `docs/label_studio_config.xml`.
3. **Data Import:** přetáhni snímky ze složky `data/custom/frames/<video>/`
   (nebo *Settings → Cloud Storage → Local files*, cesta `…/data/custom/frames`).
4. Anotuj (klávesy: `1` díra, `2` trhlina, `q`–`t` povrch, `Ctrl+Enter` odeslat).
5. **Export → JSON** → ulož jako `data/custom/labelstudio_export.json`.
6. Převod do tréninkových formátů:

```bash
python -m src.dataset.import_annotations labelstudio data/custom/labelstudio_export.json
```

### Alternativa: CVAT

1. Nový task s labely `díra` a `trhlina` (typ rectangle), nahraj snímky.
2. Anotuj, **Export task dataset → YOLO 1.1** (bez „Save images“ stačí).
3. `python -m src.dataset.import_annotations yolo <rozbalený_export>/`

Třídy se mapují podle názvu (díra/dira/pothole/výtluk → díra), pořadí v exportu nevadí.
Povrch se z CVAT nepřenese – pro klasifikaci pak použij jen veřejná data, nebo povrchy
doplň v Label Studiu.

## 4. Pravidla anotace (důležité pro konzistenci)

* **Díra (výtluk):** propadlina s viditelnou hranou, kde chybí kus asfaltu.
  Rámeček těsně kolem celé díry včetně okraje. Vyspravenou (zalátanou) díru neoznačuj.
* **Trhlina:** podélná, příčná i síťová. Dlouhou podélnou trhlinu rozděl na víc
  rámečků (každý max. ~1/4 šířky obrazu), jinak jsou boxy obří a nepřesné.
* Označuj jen objekty **na vozovce před motorkou** do vzdálenosti, kde jsou ještě
  rozeznatelné (zhruba menší než 15 × 15 px už ne).
* **Povrch** = povrch ve spodní třetině obrazu (kam motorka za chvíli vjede).
  Když je mokrý asfalt → „mokro“; mokrá šotolina → „štěrk“ (štěrk je nebezpečnější).
* **Podmínky** zaškrtni, co pro snímek platí (stín přes vozovku, noc, rozmazané, …).
* Nejisté snímky raději přeskoč (*Skip*), než je označit špatně.

## 5. Rozdělení dat

`import_annotations` dělí **po celých videích** (train / val / test ≈ 60 / 15 / 25 %
snímků). Konkrétní testovací jízdy můžeš určit sám:

```bash
python -m src.dataset.import_annotations labelstudio export.json --test-videos 2026-10-05_jizda4
```

Testovací jízdy se nesmí použít k tréninku ani k ladění parametrů – jen k finálnímu
vyhodnocení v kapitole Experimenty.
