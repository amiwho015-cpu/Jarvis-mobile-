# Jarvis for Android (OpenJarvis-style)

Native Android app, inspired by the OpenJarvis framework (agents, tools, memory, engines).

## Agents (top-right dropdown in chat)
- assistant: tool-using agent (time, calculator, open app, memory, battery, weather, news, web search)
- simple: plain chat, no tools
- research: deep research (web search + page fetch + cross-check)
- briefing: spoken morning digest (weather + BBC headlines + saved notes)

## Memory
Say "remember that my sister's birthday is 5 May". Facts are stored on the phone and given to every agent.
"recall" lists them, "forget ..." removes them. Settings > Show/Clear memory.

## Engines
- gemini (default): Google Gemini API, key from aistudio.google.com
- local: any OpenAI-compatible server (Ollama, LM Studio, llama.cpp) on your PC or another device,
  e.g. http://192.168.1.10:11434/v1 . Set Engine = local in Settings.

## Voice
- Mic button in chat (Google speech dialog)
- Settings > Start voice service: always listening, say "<wake word>, ..." (works with screen off)

## Build
Push to GitHub (main). Actions > Build APK > Artifacts > jarvis-debug-apk.

## Limits
- Voice service uses Google speech recognition: needs internet, uses battery.
- Android does not allow the mic service to auto-start after reboot (Android 14).
- Not included from OpenJarvis (needs a PC/server): Rust core, learning loop, benchmarks, 25+ channels.
