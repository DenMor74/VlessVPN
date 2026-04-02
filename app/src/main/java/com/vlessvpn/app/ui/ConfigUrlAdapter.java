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

        holder.checkBox.setChecked(item.isEnabled());
        holder.editText.setText(item.getUrl());

        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.setEnabled(isChecked);
            if (listener != null) listener.onUrlChanged();
        });

        holder.editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                item.setUrl(holder.editText.getText().toString().trim());
                if (listener != null) listener.onUrlChanged();
            }
        });

        holder.btnDelete.setOnClickListener(v -> {
            items.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, items.size());
            if (listener != null) listener.onUrlChanged();
        });
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