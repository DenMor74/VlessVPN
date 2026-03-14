package com.vlessvpn.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vlessvpn.app.R;
import com.vlessvpn.app.model.VlessServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ServerAdapter — отображает список серверов в RecyclerView.
 *
 * Каждая строка показывает:
 *  - Иконку статуса (⬜ нет данных / ⏳ тестируется / ✅ рабочий / ❌ нерабочий)
 *  - Host:port + название
 *  - Пинг (зелёный = быстро, жёлтый = средне, красный = медленно)
 *  - Детали теста (TCP ping → трафик)
 *  - Кнопку "Подключить" для рабочих серверов
 */
public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.ViewHolder> {

    public interface OnConnectListener {
        void onConnect(VlessServer server);
    }

    /** Статус тестирования конкретного сервера */
    public enum TestStatus {
        IDLE,       // ⬜ не тестировался
        PINGING,    // ⏳ идёт TCP ping
        TESTING,    // ⏳ идёт тест трафика
        OK,         // ✅ рабочий
        FAIL        // ❌ нерабочий
    }

    private List<VlessServer> servers = new ArrayList<>();
    private final Map<String, TestStatus> statusMap = new HashMap<>();
    private final Map<String, String> detailMap = new HashMap<>();
    private final OnConnectListener connectListener;
    private String connectedServerId = null;

    public ServerAdapter(OnConnectListener listener) {
        this.connectListener = listener;
    }

    /** Обновить весь список (из Room LiveData) */
    public void setServers(List<VlessServer> newList) {
        this.servers = newList != null ? newList : new ArrayList<>();
        notifyDataSetChanged();
    }

    /** Обновить статус конкретного сервера во время теста */
    public void updateServerStatus(String serverId, TestStatus status, String detail) {
        statusMap.put(serverId, status);
        if (detail != null) detailMap.put(serverId, detail);
        // Находим позицию и обновляем только её
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id.equals(serverId)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** Добавить сервер в список (при загрузке из сети, до финального Room обновления) */
    public void addOrUpdateServer(VlessServer server) {
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id.equals(server.id)) {
                servers.set(i, server);
                notifyItemChanged(i);
                return;
            }
        }
        servers.add(server);
        notifyItemInserted(servers.size() - 1);
    }

    public void setConnectedServerId(String id) {
        this.connectedServerId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_server, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        VlessServer s = servers.get(position);
        TestStatus status = statusMap.getOrDefault(s.id, TestStatus.IDLE);
        String detail = detailMap.getOrDefault(s.id, "");
        boolean isConnected = s.id.equals(connectedServerId);

        // ── Host + remark ─────────────────────────────────
        h.tvHost.setText(s.host + ":" + s.port);
        h.tvRemark.setText(s.remark.isEmpty() ? s.host : s.remark);

        // ── Иконка статуса ────────────────────────────────
        String icon;
        int pingColor;
        switch (status) {
            case PINGING: icon = "⏳"; break;
            case TESTING: icon = "🔄"; break;
            case OK:      icon = isConnected ? "🟢" : "✅"; break;
            case FAIL:    icon = "❌"; break;
            default:
                // Используем данные из БД если статус неизвестен
                if (s.trafficOk)       icon = isConnected ? "🟢" : "✅";
                else if (s.pingMs > 0) icon = "⚠️";
                else                   icon = "⬜";
        }
        h.tvIcon.setText(icon);

        // ── Пинг ─────────────────────────────────────────
        if (s.pingMs > 0) {
            h.tvPing.setText(s.pingMs + "ms");
            if (s.pingMs < 100)      h.tvPing.setTextColor(0xFF4CAF50); // зелёный
            else if (s.pingMs < 300) h.tvPing.setTextColor(0xFFFFB300); // жёлтый
            else                     h.tvPing.setTextColor(0xFFFF5555); // красный
        } else if (status == TestStatus.PINGING) {
            h.tvPing.setText("ping...");
            h.tvPing.setTextColor(0xFF7799BB);
        } else if (status == TestStatus.TESTING) {
            h.tvPing.setText("VLESS...");
            h.tvPing.setTextColor(0xFFFFB300);
        } else {
            h.tvPing.setText("—");
            h.tvPing.setTextColor(0xFF556677);
        }

        // ── Детали теста ─────────────────────────────────
        if (detail != null && !detail.isEmpty()) {
            h.tvStatus.setText(detail);
            h.tvStatus.setVisibility(View.VISIBLE);
        } else if (status == TestStatus.TESTING) {
            String testDetail = detailMap.getOrDefault(s.id, "");
            h.tvStatus.setText(testDetail.isEmpty() ? "⏳ VLESS проверка..." : testDetail);
            h.tvStatus.setVisibility(View.VISIBLE);
        } else if (status == TestStatus.OK) {
            String d = detailMap.getOrDefault(s.id, "");
            h.tvStatus.setText(d.isEmpty() ? (s.pingMs > 0 ? "✓ " + s.pingMs + "ms" : "✓ OK") : d);
            h.tvStatus.setVisibility(View.VISIBLE);
        } else if (status == TestStatus.FAIL) {
            h.tvStatus.setText("✗ недоступен");
            h.tvStatus.setVisibility(View.VISIBLE);
        } else if (s.trafficOk && s.pingMs > 0) {
            // Сервер рабочий из БД — показываем последний результат
            h.tvStatus.setText("✓ VLESS " + s.pingMs + "ms");
            h.tvStatus.setVisibility(View.VISIBLE);
        } else {
            h.tvStatus.setVisibility(View.GONE);
        }

        // ── Кнопка подключить ─────────────────────────────
        if (s.trafficOk || status == TestStatus.OK) {
            h.btnConnect.setVisibility(View.VISIBLE);
            if (isConnected) {
                h.btnConnect.setText("●");
                h.btnConnect.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF4CAF50));
            } else {
                h.btnConnect.setText("▶");
                h.btnConnect.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1A56DB));
            }
            h.btnConnect.setOnClickListener(v -> connectListener.onConnect(s));
        } else {
            h.btnConnect.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() { return servers.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvIcon, tvHost, tvRemark, tvPing, tvStatus;
        Button btnConnect;

        ViewHolder(View v) {
            super(v);
            tvIcon    = v.findViewById(R.id.tv_server_icon);
            tvHost    = v.findViewById(R.id.tv_server_host);
            tvRemark  = v.findViewById(R.id.tv_server_remark);
            tvPing    = v.findViewById(R.id.tv_server_ping);
            tvStatus  = v.findViewById(R.id.tv_server_status);
            btnConnect= v.findViewById(R.id.btn_connect);
        }
    }
}
