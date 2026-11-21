package com.example.carritobluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Control del carrito por Bluetooth de bajo consumo (BLE).
 * Funciona con módulos tipo JDY-16, HM-10, AT-09 o BT05: todos exponen un
 * "puerto serie" en el servicio FFE0, característica FFE1. Lo que se escribe
 * ahí le llega al Arduino por TX/RX como si fuera un HC-05.
 */
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final long SCAN_DURATION_MS = 6000;
    private static final long CONNECT_TIMEOUT_MS = 12000;

    private static final UUID SERIAL_SERVICE =
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID SERIAL_CHARACTERISTIC =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txCharacteristic;
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    private volatile boolean ready = false;
    private boolean scanning = false;
    private String connectedName = "";

    private final Handler handler = new Handler(Looper.getMainLooper());

    // BLE solo admite una escritura a la vez: los comandos esperan su turno aquí.
    private final ArrayDeque<Byte> commandQueue = new ArrayDeque<>();
    private boolean writing = false;

    private Spinner deviceSpinner;
    private TextView statusText;
    private ArrayAdapter<String> spinnerAdapter;

    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private final ArrayList<String> deviceNames = new ArrayList<>();
    private final ArrayList<String> deviceLabels = new ArrayList<>();

    private final Runnable stopScanRunnable = this::stopScan;

    private final Runnable connectTimeoutRunnable = () -> {
        if (!ready && gatt != null) {
            disconnect();
            setStatus("el carrito no respondió. ¿Está encendido y cerca?");
        }
    };

    private final Runnable writeWatchdog = () -> {
        writing = false;
        pumpQueue();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager != null ? manager.getAdapter() : null;

        setContentView(buildInterface());

        if (hasPermissions()) {
            startScan();
        } else {
            requestPermissions(neededPermissions(), REQUEST_PERMISSIONS);
        }
    }

    // ---------------------------------------------------------------- permisos

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;

        if (hasPermissions()) {
            startScan();
        } else {
            String which = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? "Dispositivos cercanos"
                    : "Ubicación";
            toast("La app necesita el permiso de " + which + " para encontrar el carrito.");
            setStatus("falta el permiso de " + which);
        }
    }

    // ---------------------------------------------------------------- búsqueda

    private void startScan() {
        if (bluetoothAdapter == null) {
            setStatus("este teléfono no tiene Bluetooth");
            return;
        }
        if (!hasPermissions()) {
            requestPermissions(neededPermissions(), REQUEST_PERMISSIONS);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            setStatus("activa el Bluetooth del teléfono");
            toast("Activa el Bluetooth y vuelve a pulsar Buscar.");
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("no se pudo iniciar la búsqueda");
            return;
        }

        stopScan();
        devices.clear();
        deviceNames.clear();
        refreshDeviceList("Buscando...");

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(null, settings, scanCallback);
        scanning = true;
        setStatus("buscando el carrito...");

        handler.removeCallbacks(stopScanRunnable);
        handler.postDelayed(stopScanRunnable, SCAN_DURATION_MS);
    }

    private void stopScan() {
        handler.removeCallbacks(stopScanRunnable);
        if (!scanning) return;
        scanning = false;

        if (scanner != null && hasPermissions()) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }

        if (devices.isEmpty()) {
            refreshDeviceList("No se encontró nada");
            String hint = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    ? " (en este Android también hay que encender la Ubicación)"
                    : "";
            setStatus("no se encontró el carrito. ¿Está encendido?" + hint);
        } else if (!ready) {
            setStatus("elige el carrito y pulsa Conectar");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();

            String name = record != null ? record.getDeviceName() : null;
            if (name == null) name = device.getName();
            if (name == null || name.trim().isEmpty()) return; // sin nombre: no es el carrito

            final String finalName = name.trim();
            runOnUiThread(() -> addDevice(device, finalName));
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            setStatus("falló la búsqueda (código " + errorCode + "). Pulsa Buscar otra vez.");
        }
    };

    private void addDevice(BluetoothDevice device, String name) {
        for (BluetoothDevice d : devices) {
            if (d.getAddress().equals(device.getAddress())) return;
        }

        // Los módulos de carrito van hasta arriba para que queden seleccionados.
        String upper = name.toUpperCase();
        boolean looksLikeCar = upper.contains("JDY") || upper.contains("HM")
                || upper.contains("BT05") || upper.contains("AT-09") || upper.contains("HC");

        if (looksLikeCar) {
            devices.add(0, device);
            deviceNames.add(0, name);
        } else {
            devices.add(device);
            deviceNames.add(name);
        }
        refreshDeviceList(null);
        if (looksLikeCar) deviceSpinner.setSelection(0);
    }

    private void refreshDeviceList(String placeholder) {
        deviceLabels.clear();
        if (devices.isEmpty()) {
            deviceLabels.add(placeholder != null ? placeholder : "Buscando...");
        } else {
            for (int i = 0; i < devices.size(); i++) {
                deviceLabels.add(deviceNames.get(i) + "  ·  " + devices.get(i).getAddress());
            }
        }
        spinnerAdapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- conexión

    private void connectSelectedDevice() {
        if (!hasPermissions()) {
            requestPermissions(neededPermissions(), REQUEST_PERMISSIONS);
            return;
        }
        if (devices.isEmpty()) {
            toast("Primero pulsa Buscar con el carrito encendido.");
            return;
        }
        int position = deviceSpinner.getSelectedItemPosition();
        if (position < 0 || position >= devices.size()) return;

        stopScan();
        disconnect();

        BluetoothDevice device = devices.get(position);
        connectedName = deviceNames.get(position);
        setStatus("conectando a " + connectedName + "...");

        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);

        handler.removeCallbacks(connectTimeoutRunnable);
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    private void disconnect() {
        ready = false;
        txCharacteristic = null;
        handler.removeCallbacks(connectTimeoutRunnable);
        handler.removeCallbacks(writeWatchdog);
        commandQueue.clear();
        writing = false;

        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("conectado, preparando...");
                // Una pausa corta antes de pedir los servicios evita fallos en algunos teléfonos.
                handler.postDelayed(() -> {
                    if (gatt == g) g.discoverServices();
                }, 300);
                return;
            }

            boolean wasReady = ready;
            handler.post(() -> {
                if (gatt == g) {
                    disconnect();
                } else {
                    g.close();
                }
                if (wasReady) {
                    setStatus("se perdió la conexión");
                    toast("Se perdió la conexión con el carrito");
                } else {
                    setStatus("no se pudo conectar (código " + status + "). Intenta otra vez.");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post(() -> {
                    disconnect();
                    setStatus("no se pudieron leer los servicios. Intenta otra vez.");
                });
                return;
            }

            BluetoothGattCharacteristic characteristic = null;
            BluetoothGattService service = g.getService(SERIAL_SERVICE);
            if (service != null) {
                characteristic = service.getCharacteristic(SERIAL_CHARACTERISTIC);
            }
            if (characteristic == null) {
                characteristic = findAnyWritableCharacteristic(g);
            }

            final BluetoothGattCharacteristic found = characteristic;
            handler.post(() -> {
                if (found == null) {
                    disconnect();
                    setStatus("ese dispositivo no es un módulo del carrito");
                    return;
                }

                txCharacteristic = found;
                writeType = (found.getProperties()
                        & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);

                ready = true;
                handler.removeCallbacks(connectTimeoutRunnable);
                setStatus("conectado a " + connectedName);
                toast("Carrito conectado");
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            handler.post(() -> {
                handler.removeCallbacks(writeWatchdog);
                writing = false;
                pumpQueue();
            });
        }
    };

    private BluetoothGattCharacteristic findAnyWritableCharacteristic(BluetoothGatt g) {
        int writable = BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;

        for (BluetoothGattService s : g.getServices()) {
            String uuid = s.getUuid().toString().toLowerCase();
            // Saltar los servicios genéricos que trae todo dispositivo BLE.
            if (uuid.startsWith("00001800") || uuid.startsWith("00001801")
                    || uuid.startsWith("0000180a")) {
                continue;
            }
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if ((c.getProperties() & writable) != 0) return c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- envío

    private void sendCommand(char command) {
        if (!ready || gatt == null || txCharacteristic == null) {
            toast("Primero conecta el carrito");
            return;
        }
        commandQueue.add((byte) command);
        pumpQueue();
    }

    private void pumpQueue() {
        if (writing || commandQueue.isEmpty() || !ready || gatt == null || txCharacteristic == null) {
            return;
        }

        byte value = commandQueue.peek();
        if (writeBytes(new byte[]{value})) {
            commandQueue.poll();
            writing = true;
            handler.postDelayed(writeWatchdog, 500);
        } else {
            // El teléfono estaba ocupado: reintentar en un momento.
            handler.postDelayed(this::pumpQueue, 30);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean writeBytes(byte[] value) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return gatt.writeCharacteristic(txCharacteristic, value, writeType)
                        == BluetoothStatusCodes.SUCCESS;
            }
            txCharacteristic.setWriteType(writeType);
            txCharacteristic.setValue(value);
            return gatt.writeCharacteristic(txCharacteristic);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        if (ready && gatt != null && txCharacteristic != null) {
            writeBytes(new byte[]{(byte) 'S'}); // que no se quede andando
        }
        stopScan();
        disconnect();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- pantalla

    private View buildInterface() {
        int pad = dp(16);
        int gap = dp(10);

        ScrollView scrollView = new ScrollView(this);
        // Deja espacio para la barra de la hora y la de navegación (Android 15+).
        scrollView.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Control Carrito Bluetooth");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, gap);
        root.addView(title, matchWrap());

        TextView instruction = new TextView(this);
        instruction.setText("Enciende el carrito, pulsa Buscar, elige el JDY-16 y pulsa Conectar. No hace falta emparejarlo en Ajustes.");
        instruction.setTextSize(15);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, 0, 0, gap);
        root.addView(instruction, matchWrap());

        deviceSpinner = new Spinner(this);
        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, deviceLabels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(spinnerAdapter);
        root.addView(deviceSpinner, matchWrap());

        Button search = button("Buscar carrito");
        search.setOnClickListener(v -> startScan());
        root.addView(search, matchWrapWithTop(gap));

        Button connect = button("Conectar");
        connect.setOnClickListener(v -> connectSelectedDevice());
        root.addView(connect, matchWrapWithTop(gap));

        statusText = new TextView(this);
        statusText.setText("Estado: desconectado");
        statusText.setTextSize(16);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, gap, 0, gap * 2);
        root.addView(statusText, matchWrap());

        TextView controls = new TextView(this);
        controls.setText("Mantén presionada una dirección. Al soltar, envía STOP.");
        controls.setTextSize(15);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, 0, 0, gap);
        root.addView(controls, matchWrap());

        Button forward = directionButton("↑");
        setHoldCommand(forward, 'F');
        root.addView(forward, squareParams(90));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        middle.setGravity(Gravity.CENTER);

        Button left = directionButton("←");
        Button stop = button("STOP");
        Button right = directionButton("→");

        setHoldCommand(left, 'L');
        stop.setOnClickListener(v -> sendCommand('S'));
        setHoldCommand(right, 'R');

        middle.addView(left, squareParams(90));
        LinearLayout.LayoutParams stopParams = squareParams(100);
        stopParams.setMargins(gap, 0, gap, 0);
        middle.addView(stop, stopParams);
        middle.addView(right, squareParams(90));
        root.addView(middle, matchWrapWithTop(gap));

        Button backward = directionButton("↓");
        setHoldCommand(backward, 'B');
        root.addView(backward, squareParamsWithTop(90, gap));

        TextView speedTitle = new TextView(this);
        speedTitle.setText("Velocidad");
        speedTitle.setTextSize(18);
        speedTitle.setGravity(Gravity.CENTER);
        speedTitle.setPadding(0, gap * 2, 0, gap);
        root.addView(speedTitle, matchWrap());

        LinearLayout speedRow1 = new LinearLayout(this);
        speedRow1.setOrientation(LinearLayout.HORIZONTAL);
        speedRow1.setGravity(Gravity.CENTER);

        LinearLayout speedRow2 = new LinearLayout(this);
        speedRow2.setOrientation(LinearLayout.HORIZONTAL);
        speedRow2.setGravity(Gravity.CENTER);

        for (int i = 0; i <= 9; i++) {
            final char command = Character.forDigit(i, 10);
            Button speed = button(String.valueOf(i));
            speed.setOnClickListener(v -> sendCommand(command));

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(56), dp(52));
            p.setMargins(dp(3), dp(3), dp(3), dp(3));

            if (i <= 4) {
                speedRow1.addView(speed, p);
            } else {
                speedRow2.addView(speed, p);
            }
        }
        root.addView(speedRow1, matchWrap());
        root.addView(speedRow2, matchWrap());

        TextView commandHelp = new TextView(this);
        commandHelp.setText("F = adelante · B = atrás · L = izquierda · R = derecha · S = detener · 0–9 = velocidad");
        commandHelp.setGravity(Gravity.CENTER);
        commandHelp.setTextSize(13);
        commandHelp.setPadding(0, gap * 2, 0, gap);
        root.addView(commandHelp, matchWrap());

        return scrollView;
    }

    private void setHoldCommand(Button button, char command) {
        button.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                sendCommand(command);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                sendCommand('S');
                v.performClick();
                return true;
            }
            return false;
        });
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusText.setText("Estado: " + text));
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        return b;
    }

    private Button directionButton(String text) {
        Button b = button(text);
        b.setTextSize(32);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int top) {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = top;
        return p;
    }

    private LinearLayout.LayoutParams squareParams(int sizeDp) {
        return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
    }

    private LinearLayout.LayoutParams squareParamsWithTop(int sizeDp, int top) {
        LinearLayout.LayoutParams p = squareParams(sizeDp);
        p.topMargin = top;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
