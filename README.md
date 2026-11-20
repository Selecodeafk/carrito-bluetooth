# Carrito Bluetooth HC-05

App Android nativa para controlar un carrito Arduino mediante Bluetooth clásico HC-05.

## Comandos enviados
- F: adelante
- B: atrás
- L: izquierda
- R: derecha
- S: detener
- 0 a 9: velocidad

## Antes de usar la app
1. Enciende el Arduino y el HC-05.
2. En Android, abre Ajustes > Bluetooth.
3. Empareja el HC-05. Los PIN más comunes son 1234 o 0000.
4. Abre la app.
5. Concede el permiso de dispositivos cercanos si Android lo solicita.
6. Pulsa "Actualizar dispositivos".
7. Selecciona el HC-05 y pulsa "Conectar al HC-05".

## Generar el APK en Android Studio
1. Abre esta carpeta como proyecto en Android Studio.
2. Espera a que termine Gradle Sync.
3. Si Android Studio pide instalar Android SDK 36 / Build Tools, acepta.
4. Ve a Build > Build APK(s).
5. El APK de depuración normalmente queda en:
   app/build/outputs/apk/debug/app-debug.apk

## Hardware Arduino usado en este proyecto
HC-05:
- TXD -> Arduino D2
- RXD -> Arduino D3
- VCC -> 5V
- GND -> GND

L298N:
- ENA -> D5
- ENB -> D6
- IN1 -> D13
- IN2 -> D12
- IN3 -> D8
- IN4 -> D10
