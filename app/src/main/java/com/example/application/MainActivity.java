package com.example.application;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

public class MainActivity extends AppCompatActivity implements TcpClient.Listener {

    private static final String PREFS = "smarthome";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";

    private static final String DEFAULT_HOST = "192.168.1.199";
    private static final int DEFAULT_PORT = 8080;

    private MaterialCardView cardConn, cardLight, cardFan;
    private TextView tvConnState, tvConnHint, tvLightState, tvFanState;
    private MaterialButton btnLight, btnFan;

    private TcpClient client;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        cardConn = findViewById(R.id.cardConn);
        cardLight = findViewById(R.id.cardLight);
        cardFan = findViewById(R.id.cardFan);
        tvConnState = findViewById(R.id.tvConnState);
        tvConnHint = findViewById(R.id.tvConnHint);
        tvLightState = findViewById(R.id.tvLightState);
        tvFanState = findViewById(R.id.tvFanState);
        btnLight = findViewById(R.id.btnLight);
        btnFan = findViewById(R.id.btnFan);

        client = new TcpClient(this);

        cardConn.setOnClickListener(v -> showAddressDialog());

        // 发出去的是"翻转"指令，所以界面不在这里改。
        // 必须等 51 回了 sXX 才更新显示，这样板上按键和手机永远一致。
        btnLight.setOnClickListener(v -> sendCommand("i1"));
        btnFan.setOnClickListener(v -> sendCommand("i2"));

        onConnectionChanged(false);
        onDeviceStatus(false, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startClient();
    }

    @Override
    protected void onStop() {
        super.onStop();
        client.stop();          // 退到后台就断开，不白占着 ESP 的连接
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        client.release();       // 回收发送线程，避免转屏反复重建时泄漏
    }

    // ------------------------------------------------------------------

    private String host() {
        return prefs.getString(KEY_HOST, DEFAULT_HOST);
    }

    private int port() {
        return prefs.getInt(KEY_PORT, DEFAULT_PORT);
    }

    private void startClient() {
        tvConnHint.setText(host() + ":" + port());
        client.start(host(), port());
    }

    private void sendCommand(String cmd) {
        if (!client.isConnected()) {
            Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        client.send(cmd);
    }

    private void showAddressDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_ip, null);
        EditText etIp = view.findViewById(R.id.etIp);
        EditText etPort = view.findViewById(R.id.etPort);
        etIp.setText(host());
        etPort.setText(String.valueOf(port()));

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title)
                .setView(view)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (d, which) -> {
                    String ip = etIp.getText().toString().trim();
                    if (TextUtils.isEmpty(ip)) {
                        Toast.makeText(this, R.string.toast_bad_ip, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int p = DEFAULT_PORT;
                    try {
                        p = Integer.parseInt(etPort.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    prefs.edit().putString(KEY_HOST, ip).putInt(KEY_PORT, p).apply();
                    startClient();      // 用新地址重连
                })
                .show();
    }

    // ------------------------ TcpClient.Listener ------------------------

    @Override
    public void onConnectionChanged(boolean connected) {
        tvConnState.setText(connected ? R.string.conn_connected : R.string.conn_disconnected);
        cardConn.setCardBackgroundColor(
                color(connected ? R.color.state_connected : R.color.state_off));

        btnLight.setEnabled(connected);
        btnFan.setEnabled(connected);

        if (!connected) {
            // 断开时清掉旧状态，免得看着像还在正常控制
            onDeviceStatus(false, false);
        }
    }

    @Override
    public void onDeviceStatus(boolean light, boolean fan) {
        tvLightState.setText(light ? R.string.state_on : R.string.state_off);
        tvFanState.setText(fan ? R.string.state_on : R.string.state_off);

        cardLight.setCardBackgroundColor(
                color(light ? R.color.state_light_on : R.color.state_off));
        cardFan.setCardBackgroundColor(
                color(fan ? R.color.state_fan_on : R.color.state_off));
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }
}
