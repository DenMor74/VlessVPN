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
import com.vlessvpn.app.util.FileLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.ViewHolder> {

    private static final String TAG = "ServerAdapter";

    public interface OnConnectListener {
        void onConnect(VlessServer server);
    }

    public enum TestStatus {
        IDLE, PINGING, TESTING, OK, FAIL
    }

    private List<VlessServer> servers = new ArrayList<>();
    private final Map<String, TestStatus> statusMap = new HashMap<>();
    private final Map<String, String> detailMap = new HashMap<>();
    private final OnConnectListener connectListener;
    private String connectedServerId = null;

    public ServerAdapter(OnConnectListener listener) {
        this.connectListener = listener;
    }

    public void setServers(List<VlessServer> newList) {
        // ════════════════════════════════════════════════════════════════
        // ← ВАЖНО: Создаём НОВЫЙ список чтобы RecyclerView увидел изменения
        // ════════════════════════════════════════════════════════════════
        this.servers = newList != null ? new ArrayList<>(newList) : new ArrayList<>();

       // FileLogger.i(TAG, "setServers: " + this.servers.size() + " серверов");

        // ════════════════════════════════════════════════════════════════
        // ← ВАЖНО: Явно вызываем notifyDataSetChanged()
        // ════════════════════════════════════════════════════════════════
        notifyDataSetChanged();

       // FileLogger.i(TAG, "notifyDataSetChanged() вызван");
    }

    public void updateServerStatus(String serverId, TestStatus status, String detail) {
        statusMap.put(serverId, status);
        if (detail != null) detailMap.put(serverId, detail);

        // ════════════════════════════════════════════════════════════════
        // ← ОТЛАДКА: Логируем обновление статуса
        // ════════════════════════════════════════════════════════════════
        //FileLogger.i(TAG, "updateServerStatus: " + serverId + " → " + status + " " + detail);

        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id.equals(serverId)) {
                notifyItemChanged(i);
                //FileLogger.d(TAG, "notifyItemChanged(" + i + ")");
                return;
            }
        }
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

        h.tvHost.setText(s.host + ":" + s.port);
        h.tvRemark.setText(s.remark.isEmpty() ? s.host : s.remark);

        // ── Иконка статуса ─────────────────────────────────
        String icon;
        switch (status) {
            case PINGING: icon = "⏳"; break;
            case TESTING: icon = "🔄"; break;
            case OK:      icon = isConnected ? "🟢" : "✅"; break;
            case FAIL:    icon = "❌"; break;
            default:
                if (s.trafficOk)       icon = isConnected ? "🟢" : "✅";
                else if (s.pingMs > 0) icon = "⚠️";
                else                   icon = "⬜";
        }
        h.tvIcon.setText(icon);

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Показываем TCP пинг и VLESS задержку
        // ════════════════════════════════════════════════════════════════

        // Получаем TCP пинг из detail (формат: "TCP 45ms → VLESS...")
        int tcpPing = extractTcpPing(detail);

        // server.pingMs = VLESS задержка (после успешного теста)
        int vlessDelay = s.pingMs > 0 ? (int) s.pingMs : -1;

        if (status == TestStatus.PINGING) {
            h.tvPing.setText("TCP...");
            h.tvPing.setTextColor(0xFF7799BB);
        } else if (status == TestStatus.TESTING) {
            // Во время VLESS теста показываем оба значения
            if (tcpPing > 0) {
                h.tvPing.setText("TCP " + tcpPing + "ms");
            } else {
                h.tvPing.setText("VLESS...");
            }
            h.tvPing.setTextColor(0xFFFFB300);
        } else if (status == TestStatus.OK || s.trafficOk) {
            // ════════════════════════════════════════════════════════════════
            // ← ПОСЛЕ УСПЕШНОГО ТЕСТА: показываем оба значения
            // ════════════════════════════════════════════════════════════════
            if (tcpPing > 0 && vlessDelay > 0 && tcpPing != vlessDelay) {
                // Оба значения разные — показываем оба
                h.tvPing.setText(tcpPing + "→" + vlessDelay);
                // Цвет по VLESS задержке (она важнее)
                if (vlessDelay < 100)      h.tvPing.setTextColor(0xFF4CAF50);
                else if (vlessDelay < 300) h.tvPing.setTextColor(0xFFFFB300);
                else                       h.tvPing.setTextColor(0xFFFF5555);
            } else if (vlessDelay > 0) {
                // Только VLESS задержка
                h.tvPing.setText(vlessDelay + "ms");
                if (vlessDelay < 100)      h.tvPing.setTextColor(0xFF4CAF50);
                else if (vlessDelay < 300) h.tvPing.setTextColor(0xFFFFB300);
                else                       h.tvPing.setTextColor(0xFFFF5555);
            } else if (tcpPing > 0) {
                // Только TCP пинг
                h.tvPing.setText(tcpPing + "ms");
                if (tcpPing < 100)      h.tvPing.setTextColor(0xFF4CAF50);
                else if (tcpPing < 300) h.tvPing.setTextColor(0xFFFFB300);
                else                    h.tvPing.setTextColor(0xFFFF5555);
            } else {
                h.tvPing.setText("—");
                h.tvPing.setTextColor(0xFF556677);
            }
        } else if (status == TestStatus.FAIL || !s.trafficOk) {
            if (tcpPing > 0) {
                h.tvPing.setText(tcpPing + "ms");
                h.tvPing.setTextColor(0xFFFF5555);
            } else {
                h.tvPing.setText("✗");
                h.tvPing.setTextColor(0xFFFF5555);
            }
        } else {
            h.tvPing.setText("—");
            h.tvPing.setTextColor(0xFF556677);
        }

        // ── Детали теста ─────────────────────────────────
        if (detail != null && !detail.isEmpty()) {
            h.tvStatus.setText(detail);
            h.tvStatus.setVisibility(View.VISIBLE);
        } else if (status == TestStatus.TESTING) {
            h.tvStatus.setText("⏳ VLESS проверка...");
            h.tvStatus.setVisibility(View.VISIBLE);
        } else if (status == TestStatus.OK || s.trafficOk) {
            h.tvStatus.setText(vlessDelay > 0 ? "✓ VLESS " + vlessDelay + "ms" : "✓ OK");
            h.tvStatus.setVisibility(View.VISIBLE);
        } else if (status == TestStatus.FAIL || !s.trafficOk) {
            h.tvStatus.setText("✗ недоступен");
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

// ════════════════════════════════════════════════════════════════
// ← НОВЫЙ МЕТОД: Извлечь TCP пинг из строки деталей
// ════════════════════════════════════════════════════════════════

    private int extractTcpPing(String detail) {
        if (detail == null || detail.isEmpty()) return -1;

        // Формат: "TCP 45ms → VLESS..."
        try {
            if (detail.startsWith("TCP ")) {
                int end = detail.indexOf("ms");
                if (end > 4) {
                    String pingStr = detail.substring(4, end).trim();
                    return Integer.parseInt(pingStr);
                }
            }
        } catch (Exception e) {
            // Игнорируем ошибки парсинга
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        int count = servers.size();
       // FileLogger.d(TAG, "getItemCount: " + count);
        return count;
    }

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