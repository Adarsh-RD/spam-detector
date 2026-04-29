package com.example.spamdetection;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    private final List<CapturedMessage> messages = new ArrayList<>();
    private final OnFeedbackListener feedbackListener;

    public interface OnFeedbackListener {
        void onFeedback(CapturedMessage message, int label, int position);
    }

    public MessageAdapter(OnFeedbackListener listener) {
        this.feedbackListener = listener;
    }

    public void addMessage(CapturedMessage message) {
        messages.add(0, message);
        notifyItemInserted(0);
    }

    public void removeMessage(int position) {
        messages.remove(position);
        notifyItemRemoved(position);
    }

    public void clearAll() {
        int size = messages.size();
        messages.clear();
        notifyItemRangeRemoved(0, size);
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        CapturedMessage msg = messages.get(position);
        holder.header.setText(msg.header);
        holder.content.setText(msg.displayText);

        holder.btnSpam.setOnClickListener(v -> feedbackListener.onFeedback(msg, 1, holder.getAdapterPosition()));
        holder.btnHam.setOnClickListener(v -> feedbackListener.onFeedback(msg, 0, holder.getAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView header, content;
        Button btnSpam, btnHam;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            header = itemView.findViewById(R.id.text_header);
            content = itemView.findViewById(R.id.text_content);
            btnSpam = itemView.findViewById(R.id.btn_report_spam);
            btnHam = itemView.findViewById(R.id.btn_not_spam);
        }
    }

    public static class CapturedMessage {
        public String header;
        public String displayText;
        public String rawContent;

        public CapturedMessage(String header, String displayText, String rawContent) {
            this.header = header;
            this.displayText = displayText;
            this.rawContent = rawContent;
        }
    }
}
