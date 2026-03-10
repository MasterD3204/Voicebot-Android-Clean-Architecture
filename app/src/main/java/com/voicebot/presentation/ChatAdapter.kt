package com.voicebot.presentation

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.voicebot.R
import com.voicebot.databinding.ItemChatBinding

/**
 * RecyclerView adapter for the chat log.
 *
 * Message format: "User: <text>"  or  "Bot: <text>"
 * System messages are rendered as bot-side bubbles.
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<String>()

    fun addMessage(msg: String) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(msg: String) {
        if (messages.isNotEmpty()) {
            messages[messages.size - 1] = msg
            notifyItemChanged(messages.size - 1)
        } else {
            addMessage(msg)
        }
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val raw = messages[position]
        val isUser = raw.startsWith("User:")
        val content = raw.removePrefix("User:").removePrefix("Bot:").removePrefix("System:").trim()

        with(holder.binding) {
            tvContent.text = content
            if (isUser) {
                rootChat.gravity = Gravity.END
                ivBotAvatar.visibility = View.GONE
                spacer.visibility = View.VISIBLE
                chatBubble.setBackgroundResource(R.drawable.bg_chat_user)
                tvName.text = "Bạn"
            } else {
                rootChat.gravity = Gravity.START
                ivBotAvatar.visibility = View.VISIBLE
                spacer.visibility = View.GONE
                chatBubble.setBackgroundResource(R.drawable.bg_chat_bot)
                tvName.text = "AI Assistant"
            }
        }
    }

    override fun getItemCount() = messages.size

    class ChatViewHolder(val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root)
}
