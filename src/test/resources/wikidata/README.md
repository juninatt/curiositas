# Saved answers from Wikidata

Real items from Wikidata, saved so the tests never depend on Wikidata being reachable or
unchanged. Each file is one item as Wikidata's API gives it in `entities`, fetched on 3 November
2026 with:

```
https://www.wikidata.org/w/api.php?action=wbgetentities&props=labels|claims&languages=en&format=json&ids=Q1048
```

To keep the files small and readable, only the properties Curiositas reads are kept, and the
references of each statement are left out:

| Property | Meaning |
|---|---|
| `P569`, `P570`, `P1317` | Date of birth, date of death, floruit |
| `P19`, `P20` | Place of birth, place of death |
| `P21` | Sex or gender |
| `P1477`, `P1449`, `P742` | Birth name, nickname, pseudonym |
| `P17`, `P297` | Country, and its ISO 3166-1 alpha-2 code (for places and countries) |

| File | Item | Why it is here |
|---|---|---|
| `Q1048.json` | Julius Caesar | Dates before Christ, a preferred date known only to the month, several disagreeing birth dates in two calendars |
| `Q231549.json` | Anne Bonny | A deprecated date, an approximate year, and an unknown date and place of death |
