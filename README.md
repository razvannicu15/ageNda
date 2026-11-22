# ageNda — by norAz

Widget Android elegant pentru calendar. Fără reclame, fără internet: citește doar calendarul telefonului.

## Ce face
- Data de azi mare + câte evenimente ai azi
- Evenimentele grupate pe zile (Azi, Mâine, Marți 24 nov…), cu bară în culoarea calendarului
- „Acum · până la 11:30” pentru ce e în desfășurare, „în 5 zile” pentru ce urmează
- Atingi un eveniment → se deschide în Calendar; „+” adaugă eveniment
- Se actualizează singur la orice schimbare din calendar, la miezul nopții și când începe/se termină un eveniment
- Setări: temă Auto/Luminos/Întunecat, culori Material You sau Neutru, opacitate fundal, 7/14/30 zile, ce calendare apar, locație on/off, limbă RO/EN

## Build
Fiecare push pe `main` construiește APK-ul în GitHub Actions și îl pune la Releases.
Link direct: `https://github.com/razvannicu15/ageNda/releases/latest/download/ageNda.apk`

`ageNda.bat` (pune-l pe Desktop) — descarcă APK-ul, trimite modificările, instalează prin ADB.

Cheia de semnare (`app/nagenda.jks`) e în repo ca update-urile să se instaleze peste versiunea veche.
