# ProxVoice

Plugin de chat de voz por proximidad para Paper 26.3 (cliente en el navegador, sin mods).

- `/voice` -> enlace personal; abrirlo, pulsar **Conectar** y dar permiso al micrófono.
- `/voice mute` -> silenciar/activar micrófono.
- El micrófono del navegador requiere HTTPS (salvo en `localhost`). Usa un proxy inverso (ver `config.yml`).

## Compilar
Requiere JDK 25 y Maven: `mvn clean package` -> `target/ProxVoice.jar`.
El .jar también se genera automáticamente con GitHub Actions.
