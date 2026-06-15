package com.wry.deviceobserver.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wry.deviceobserver.R;
import com.wry.deviceobserver.monitor.ProcessMonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView Adapter：进程列表展示
 */
public class ProcessAdapter extends RecyclerView.Adapter<ProcessAdapter.ProcessViewHolder> {

    private List<ProcessMonitor.ProcessInfo> processes = new ArrayList<>();

    public void setProcesses(List<ProcessMonitor.ProcessInfo> processes) {
        this.processes = processes;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ProcessViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_process, parent, false);
        return new ProcessViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ProcessViewHolder holder, int position) {
        ProcessMonitor.ProcessInfo info = processes.get(position);
        holder.bind(info);
    }

    @Override
    public int getItemCount() {
        return processes.size();
    }

    static class ProcessViewHolder extends RecyclerView.ViewHolder {
        TextView tvPid;
        TextView tvName;
        TextView tvMemory;
        TextView tvThreads;
        TextView tvSuspicious;

        ProcessViewHolder(@NonNull View itemView) {
            super(itemView);
            tvPid = itemView.findViewById(R.id.tv_pid);
            tvName = itemView.findViewById(R.id.tv_name);
            tvMemory = itemView.findViewById(R.id.tv_memory);
            tvThreads = itemView.findViewById(R.id.tv_threads);
            tvSuspicious = itemView.findViewById(R.id.tv_suspicious);
        }

        void bind(ProcessMonitor.ProcessInfo info) {
            tvPid.setText(String.valueOf(info.pid));
            tvName.setText(truncate(info.name, 30));
            tvMemory.setText(formatMemory(info.vmRssKb));
            tvThreads.setText(info.threads + "T");

            if (info.suspicious) {
                tvSuspicious.setText("⚠");
                tvSuspicious.setVisibility(View.VISIBLE);
            } else {
                tvSuspicious.setVisibility(View.GONE);
            }
        }

        private String formatMemory(long kb) {
            if (kb < 1024) return kb + "KB";
            return String.format("%.1fMB", kb / 1024.0);
        }

        private String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max - 1) + "…";
        }
    }
}
