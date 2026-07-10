package com.vlessvpn.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.vlessvpn.app.R;
import com.vlessvpn.app.model.ConfigUrlItem;
import java.util.List;

public class ConfigUrlAdapter extends RecyclerView.Adapter<ConfigUrlAdapter.ViewHolder> {

    private List<ConfigUrlItem> items;
    private OnUrlChangedListener listener;

    public interface OnUrlChangedListener {
        void onUrlChanged();
    }

    public ConfigUrlAdapter(List<ConfigUrlItem> items, OnUrlChangedListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_config_url, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ConfigUrlItem item = items.get(position);

        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(item.isEnabled());

        // Отображаем упрощенное имя, если нет фокуса
        if (holder.editText.hasFocus()) {
            holder.editText.setText(item.getUrl());
        } else {
            holder.editText.setText(getSimplifiedName(item.getUrl()));
        }

        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.setEnabled(isChecked);
            if (listener != null) listener.onUrlChanged();
        });

        holder.editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                // При получении фокуса показываем полный URL для редактирования
                holder.editText.setText(item.getUrl());
            } else {
                // При потере фокуса сохраняем изменения и возвращаем упрощенный вид
                String newUrl = holder.editText.getText().toString().trim();
                if (!newUrl.isEmpty() && !newUrl.equals(getSimplifiedName(item.getUrl()))) {
                    item.setUrl(newUrl);
                    if (listener != null) listener.onUrlChanged();
                }
                holder.editText.setText(getSimplifiedName(item.getUrl()));
            }
        });

        holder.btnDelete.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos != RecyclerView.NO_POSITION) {
                items.remove(currentPos);
                notifyItemRemoved(currentPos);
                notifyItemRangeChanged(currentPos, items.size());
                if (listener != null) listener.onUrlChanged();
            }
        });
    }

    private String getSimplifiedName(String url) {
        if (url == null || url.isEmpty() || url.equals("https://")) return url;
        try {
            // Обработка Yandex Translate оберток
            if (url.contains("translate.yandex.ru/translate?url=")) {
                android.net.Uri uri = android.net.Uri.parse(url);
                String wrappedUrl = uri.getQueryParameter("url");
                if (wrappedUrl != null) {
                    return "Yandex: " + getSimplifiedName(wrappedUrl);
                }
            }

            // Специальная обработка для GitHub
            if (url.contains("github")) {
                String clean = url.replace("https://", "").replace("http://", "");
                String[] parts = clean.split("/");
                if (parts.length >= 2) {
                    // parts[0] - хост, parts[1] - пользователь
                    String user = parts[1];
                    String rawFilename = parts[parts.length - 1];
                    String filename = rawFilename.contains("?") ? rawFilename.substring(0, rawFilename.indexOf("?")) : rawFilename;
                    return user + " - " + filename;
                }
            }

            // Для остальных: хост - последний сегмент пути
            android.net.Uri uri = android.net.Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getLastPathSegment();

            if (host != null && path != null && !host.equals(path)) {
                return host + " - " + path;
            } else if (host != null) {
                return host;
            }
        } catch (Exception ignored) {}
        return url;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void addUrl(String url) {
        items.add(new ConfigUrlItem(url, true));
        notifyItemInserted(items.size() - 1);
        if (listener != null) listener.onUrlChanged();
    }

    public List<ConfigUrlItem> getItems() {
        return items;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        CheckBox checkBox;
        EditText editText;
        ImageButton btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.cb_url_enabled);
            editText = itemView.findViewById(R.id.et_url);
            btnDelete = itemView.findViewById(R.id.btn_delete_url);
        }
    }
}