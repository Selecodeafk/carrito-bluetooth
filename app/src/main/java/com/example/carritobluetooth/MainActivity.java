package com.example.carritobluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
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

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQUEST_BLUETOOTH_CONNECT = 100;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;

    private Spinner deviceSpinner;
    private TextView statusText;

    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final ArrayList<String> deviceLabels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        setContentView(buildInterface());
        requestBluetoothPermissionIfNeeded();
        loadPairedDevices();
    }

    private View buildInterface() {
        int pad = dp(16);
        int gap = dp(10);

        ScrollView scrollView = new ScrollView(this);
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
        instruction.setText("Empareja primero el HC-05 desde Ajustes de Android y luego selecciónalo aquí.");
        instruction.setTextSize(15);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, 0, 0, gap);
        root.addView(instruction, matchWrap());

        deviceSpinner = new Spinner(this);
        root.addView(deviceSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button refresh = button("Actualizar dispositivos");
        refresh.setOnClickListener(v -> loadPairedDevices());
        root.addView(refresh, matchWrapWithTop(gap));

        Button connect = button("Conectar al HC-05");
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
            if (i <= 4) speedRow1.addView(speed, p);
            else speedRow2.addView(speed, p);
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

    private void requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadPairedDevices();
            } else {
                Toast.makeText(this, "Se necesita permiso de dispositivos cercanos para conectar con el HC-05.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadPairedDevices() {
        pairedDevices.clear();
        deviceLabels.clear();

        if (bluetoothAdapter == null) {
            deviceLabels.add("Este teléfono no tiene Bluetooth");
            updateSpinner();
            return;
        }

        if (!hasBluetoothPermission()) {
            deviceLabels.add("Concede permiso de Bluetooth");
            updateSpinner();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            deviceLabels.add("Activa Bluetooth en Ajustes");
            updateSpinner();
            return;
        }

        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        if (bonded.isEmpty()) {
            deviceLabels.add("No hay dispositivos emparejados");
        } else {
            for (BluetoothDevice device : bonded) {
                pairedDevices.add(device);
                String name = device.getName() == null ? "Sin nombre" : device.getName();
                deviceLabels.add(name + "\n" + device.getAddress());
            }
        }
        updateSpinner();
    }

    private void updateSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, deviceLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);
    }

    private void connectSelectedDevice() {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissionIfNeeded();
            return;
        }
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "Empareja primero el HC-05 desde Ajustes de Android.", Toast.LENGTH_LONG).show();
            return;
        }

        int position = deviceSpinner.getSelectedItemPosition();
        if (position < 0 || position >= pairedDevices.size()) return;

        BluetoothDevice device = pairedDevices.get(position);
        statusText.setText("Estado: conectando...");

        new Thread(() -> {
            try {
                closeConnection();
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();

                runOnUiThread(() -> {
                    String name = device.getName() == null ? "HC-05" : device.getName();
                    statusText.setText("Estado: conectado a " + name);
                    Toast.makeText(this, "Bluetooth conectado", Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                closeConnection();
                runOnUiThread(() -> {
                    statusText.setText("Estado: no se pudo conectar");
                    Toast.makeText(this, "No se pudo conectar. Verifica que el HC-05 esté encendido y emparejado.", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void setHoldCommand(Button button, char command) {
        button.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sendCommand(command);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                sendCommand('S');
                v.performClick();
                return true;
            }
            return false;
        });
    }

    private void sendCommand(char command) {
        if (bluetoothSocket == null || !bluetoothSocket.isConnected() || outputStream == null) {
            Toast.makeText(this, "Primero conecta el HC-05", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                outputStream.write((byte) command);
                outputStream.flush();
            } catch (IOException e) {
                runOnUiThread(() -> {
                    statusText.setText("Estado: conexión perdida");
                    Toast.makeText(this, "Se perdió la conexión Bluetooth", Toast.LENGTH_SHORT).show();
                });
                closeConnection();
            }
        }).start();
    }

    private void closeConnection() {
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) { }
        try {
            if (bluetoothSocket != null) bluetoothSocket.close();
        } catch (IOException ignored) { }
        outputStream = null;
        bluetoothSocket = null;
    }

    @Override
    protected void onDestroy() {
        sendCommand('S');
        closeConnection();
        super.onDestroy();
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
