# Supported printers

164 Canon models have a tested profile. Every listed model was checked byte for byte against Canon's own Linux driver (see `research/RESULTS.md`).

Names are the model families as the printer reports them; a "G3010 series" profile also covers the regional variants Canon groups under it (for the G3010 series: G3410, G3411, G3415, G3510, G3515).

Not listed? If `canonctl detect` shows `IVEC` in the device ID, the generic profile will work. Please open a "new model" issue so it can get a full profile.

| Columns | Meaning |
|---|---|
| Duplex | automatic 2-sided printing |
| Borderless | at least one borderless paper size |
| Maintenance | operations offered by `canonctl` |

## PIXMA G (MegaTank / ink tank)

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| G500 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G600 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G1020 |  | yes | 4 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G1030 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G2020 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G2030 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G2060 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G2070 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G3000 |  | yes | 10 | clean, nozzle |
| G3010 |  | yes | 14 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G3020 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G3030 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G3060 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G3070 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G3080 | yes | yes | 22 | clean, deep-clean, roller, platen, nozzle, align |
| G3090 | yes | yes | 22 | clean, deep-clean, roller, platen, nozzle, align |
| G4000 |  | yes | 14 | clean, nozzle |
| G4010 |  | yes | 14 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G4070 |  | yes | 22 | clean, deep-clean, flush, roller, platen, nozzle, align |
| G4080 | yes | yes | 22 | clean, deep-clean, roller, platen, nozzle, align |
| G4090 | yes | yes | 22 | clean, deep-clean, roller, platen, nozzle, align |
| G5000 | yes | yes | 17 | clean, deep-clean, flush, nozzle, align |
| G5080 | yes | yes | 17 | clean, deep-clean, flush, nozzle, align |
| G6000 | yes | yes | 17 | clean, deep-clean, flush, nozzle, align |
| G6080 | yes | yes | 17 | clean, deep-clean, flush, nozzle, align |
| G7000 | yes | yes | 17 | clean, deep-clean, flush, nozzle, align |
| G7080 | yes | yes | 17 | clean, deep-clean, flush, nozzle, align |

## PIXMA GM (mono ink tank)

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| GM2000 | yes |  | 9 | clean, deep-clean, flush, roller, platen, nozzle, align |
| GM2080 | yes |  | 9 | clean, deep-clean, flush, roller, platen, nozzle, align |
| GM4000 | yes |  | 9 | clean, deep-clean, flush, nozzle, align |
| GM4080 | yes |  | 9 | clean, deep-clean, flush, nozzle, align |

## MAXIFY GX (ink tank)

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| GX1000 | yes |  | 18 | clean, deep-clean, nozzle, align |
| GX2000 | yes |  | 18 | clean, deep-clean, nozzle, align |
| GX3000 | yes |  | 21 | clean, deep-clean, nozzle, align |
| GX4000 | yes |  | 21 | clean, deep-clean, nozzle, align |
| GX5000 | yes |  | 20 | clean, deep-clean, flush, nozzle, align |
| GX5100 | yes |  | 20 | clean, deep-clean, nozzle, align |
| GX5500 | yes |  | 22 | clean, deep-clean, nozzle, align |
| GX6000 | yes |  | 20 | clean, deep-clean, flush, nozzle, align |
| GX6100 | yes |  | 20 | clean, deep-clean, nozzle, align |
| GX6500 | yes |  | 20 | clean, deep-clean, flush, nozzle, align |
| GX7000 | yes |  | 20 | clean, deep-clean, flush, nozzle, align |
| GX7100 | yes |  | 20 | clean, deep-clean, nozzle, align |

## PIXMA E (Ink Efficient)

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| E200 |  | yes | 5 | clean, deep-clean, roller, platen, nozzle, align |
| E300 |  | yes | 5 | clean, deep-clean, roller, platen, nozzle, align |
| E460 |  |  | 6 | clean, nozzle |
| E470 |  |  | 8 | clean, nozzle |
| E480 |  | yes | 10 | clean, nozzle |
| E3100 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| E3300 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| E3400 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| E3600 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| E4200 | yes | yes | 12 | clean, deep-clean, nozzle, align |
| E4500 | yes | yes | 10 | clean, deep-clean, nozzle, align |

## PIXMA TS

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| TS200 |  | yes | 5 | clean, deep-clean, roller, platen, nozzle, align |
| TS300 |  | yes | 5 | clean, deep-clean, roller, platen, nozzle, align |
| TS700 | yes | yes | 17 | clean, deep-clean, nozzle, align |
| TS708 | yes | yes | 17 | clean, deep-clean, nozzle, align |
| TS2400 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| TS2600 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| TS3100 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| TS3300 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| TS3400 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| TS3500 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| TS3600 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| TS3700 |  | yes | 10 | clean, deep-clean, roller, platen, nozzle, align |
| TS5000 |  | yes | 13 | clean, nozzle |
| TS5100 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TS5300 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS5350i | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS5380 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS5400 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS6000 | yes | yes | 14 | clean, nozzle |
| TS6100 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TS6130 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TS6180 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TS6200 | yes | yes | 17 | clean, deep-clean, nozzle, align |
| TS6230 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS6280 | yes | yes | 17 | clean, deep-clean, nozzle, align |
| TS6300 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS6330 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS6380 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS6400 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS6630 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS6730 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS7330 | yes | yes | 16 |  |
| TS7400 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS7430 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS7450i | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS7530 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS7600i | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS7700 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS7700A | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS7700i | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS8000 | yes | yes | 14 | clean, nozzle |
| TS8100 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TS8130 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TS8180 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TS8200 | yes | yes | 17 | clean, deep-clean, nozzle, align |
| TS8230 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS8280 | yes | yes | 17 | clean, deep-clean, nozzle, align |
| TS8300 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS8330 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS8380 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS8430 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS8530 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS8630 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS8700 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS8800 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TS9000 | yes | yes | 14 | clean, nozzle |
| TS9100 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TS9180 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TS9500 | yes | yes | 21 | clean, deep-clean, nozzle, align |
| TS9580 | yes | yes | 21 | clean, deep-clean, nozzle, align |

## PIXMA TR

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| TR150 |  | yes | 16 | clean, deep-clean, roller, platen, nozzle, align |
| TR160 |  | yes | 16 |  |
| TR703 | yes | yes | 15 | clean, deep-clean, nozzle, align |
| TR4500 | yes | yes | 12 | clean, deep-clean, nozzle, align |
| TR4600 | yes | yes | 10 | clean, deep-clean, nozzle, align |
| TR4700 | yes | yes | 10 | clean, deep-clean, nozzle, align |
| TR7000 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TR7500 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TR7530 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TR7600 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TR7800 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TR8500 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TR8530 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TR8580 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| TR8600 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TR8630 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| TR9530 | yes | yes | 20 | clean, deep-clean, nozzle, align |

## PIXMA MG

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| MG2900 |  |  | 6 | clean, nozzle |
| MG3000 |  |  | 8 | clean, nozzle |
| MG3600 | yes | yes | 12 | clean, nozzle |
| MG5600 | yes | yes | 12 | clean, nozzle |
| MG5700 | yes | yes | 12 | clean, nozzle |
| MG6600 | yes | yes | 12 | clean, nozzle |
| MG6800 | yes | yes | 12 | clean, nozzle |
| MG6900 | yes | yes | 12 | clean, nozzle |
| MG7500 | yes | yes | 12 | clean, nozzle |
| MG7700 | yes | yes | 12 | clean, nozzle |

## PIXMA MX

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| MX490 |  | yes | 10 | clean, nozzle |

## MAXIFY MB

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| MB2000 | yes |  | 12 | clean, nozzle |
| MB2100 | yes |  | 19 | clean, nozzle |
| MB2300 | yes |  | 12 | clean, nozzle |
| MB2700 | yes |  | 19 | clean, nozzle |
| MB5000 | yes |  | 12 | clean, nozzle |
| MB5100 | yes |  | 19 | clean, nozzle |
| MB5300 | yes |  | 12 | clean, nozzle |
| MB5400 | yes |  | 19 | clean, nozzle |

## MAXIFY iB

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| iB4000 | yes |  | 12 | clean, nozzle |
| iB4100 | yes |  | 19 | clean, nozzle |

## PIXMA XK

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| XK50 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| XK60 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| XK70 | yes | yes | 14 | clean, deep-clean, nozzle, align |
| XK80 | yes | yes | 17 | clean, deep-clean, nozzle, align |
| XK90 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| XK100 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| XK110 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| XK120 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| XK130 | yes | yes | 16 | clean, deep-clean, nozzle, align |
| XK500 | yes | yes | 16 | clean, deep-clean, nozzle, align |

## PIXMA iP

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| iP110 |  | yes | 11 | clean, nozzle |

## Other

| Model | Duplex | Borderless | Paper sizes | Maintenance |
|---|---|---|---|---|
| BX110 |  | yes | 16 |  |
