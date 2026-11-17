# Jarvis (Android, always-on voice assistant)

Say "Jarvis, <command>" — phone-er screen off thakleo (foreground service).

## Features
- Wake word (default: jarvis, app-e change kora jay)
- Gemini AI answers, spoken back (Android TTS)
- Commands: "Jarvis open youtube", "Jarvis what time is it", "Jarvis stop"
- Language setting (en-US, bn-BD, hi-IN ...)

## Build (GitHub Actions)
1. Repo-te push koro (main branch).
2. GitHub > Actions > "Build APK" > run sesh hole **Artifacts > jarvis-debug-apk** download koro.
3. app-debug.apk phone-e install koro.

## First run
1. App kholo, Gemini API key boshao (aistudio.google.com), Save.
2. Start Jarvis > Microphone + Notification permission Allow.
3. "Allow: battery unrestricted" ar "display over other apps" permission dao.

## Limits
- Wake word detection Google speech recognition diye hoy, tai internet lagbe, battery beshi khay, ar kichu phone-e beep shobdo hote pare.
- Phone reboot korle manually app khule Start dite hobe (Android 14 background-e mic service auto-start korte dey na).
- Gemini model name change hole app-er "Gemini model" field-e notun name boshao.
