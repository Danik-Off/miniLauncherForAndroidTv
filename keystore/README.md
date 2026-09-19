# Релизный ключ

Сюда кладётся `release.jks` и `keystore.properties` — оба в `.gitignore`, в репозиторий не попадают.

Формат `keystore.properties`:

```
KEYSTORE_FILE=keystore/release.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=minilauncher
KEY_PASSWORD=...
```

Если файлов нет, `assembleRelease` подписывает APK debug-ключом.
