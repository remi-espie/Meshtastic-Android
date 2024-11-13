package com.geeksville.mesh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.toMutableStateList
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.allViews
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.android.Logging
import com.geeksville.mesh.R
import com.geeksville.mesh.database.entity.QuickChatAction
import com.geeksville.mesh.databinding.MessagesFragmentBinding
import com.geeksville.mesh.model.Message
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.model.getChannel
import com.geeksville.mesh.ui.theme.AppTheme
import com.geeksville.mesh.util.Utf8ByteLengthFilter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

internal fun FragmentManager.navigateToMessages(contactKey: String, contactName: String) {
    val messagesFragment = MessagesFragment().apply {
        arguments = bundleOf("contactKey" to contactKey, "contactName" to contactName)
    }
    beginTransaction()
        .add(R.id.mainActivityLayout, messagesFragment)
        .addToBackStack(null)
        .commit()
}
internal fun FragmentManager.navigateToPreInitMessages(contactKey: String, contactName: String, message: String) {
    val messagesFragment = MessagesFragment().apply {
        arguments = bundleOf("contactKey" to contactKey, "contactName" to contactName, "message" to message)
    }
    beginTransaction()
        .add(R.id.mainActivityLayout, messagesFragment)
        .addToBackStack(null)
        .commit()
}

@AndroidEntryPoint
class MessagesFragment : Fragment(), Logging {

    private val actionModeCallback: ActionModeCallback = ActionModeCallback()
    private var actionMode: ActionMode? = null
    private var _binding: MessagesFragmentBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private val model: UIViewModel by activityViewModels()

    private lateinit var contactKey: String

    private val selectedList = emptyList<Message>().toMutableStateList()

    private fun onClick(message: Message) {
        if (actionMode != null) {
            onLongClick(message)
        }
    }

    private fun onLongClick(message: Message) {
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(actionModeCallback)
        }
        selectedList.apply {
            if (contains(message)) remove(message) else add(message)
        }
        if (selectedList.isEmpty()) {
            // finish action mode when no items selected
            actionMode?.finish()
        } else {
            // show total items selected on action mode title
            actionMode?.title = selectedList.size.toString()
        }
    }

    override fun onPause() {
        actionMode?.finish()
        super.onPause()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = MessagesFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        contactKey = arguments?.getString("contactKey").toString()
        val contactName = arguments?.getString("contactName").toString()
        if (arguments?.getString("message") != null) binding.messageInputText.setText(arguments?.getString("message").toString())
        binding.toolbar.title = contactName
        val channelNumber = contactKey[0].digitToIntOrNull()
        if (channelNumber == DataPacket.PKC_CHANNEL_INDEX) {
            binding.toolbar.title = "$contactName🔒"
        } else if (channelNumber != null && contactKey.substring(1) != DataPacket.ID_BROADCAST) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    model.channels.collect { channels ->
                        val channelName =
                            channels.getChannel(channelNumber)?.name ?: "Unknown Channel"
                        val subtitle = "(ch: $channelNumber - $channelName)"
                        binding.toolbar.subtitle = subtitle
                    }
                }
            }
        }

        fun sendMessageInputText() {
            val str = binding.messageInputText.text.toString().trim()
            if (str.isNotEmpty()) {
                model.sendMessage(str, contactKey)
            }
            binding.messageInputText.setText("") // blow away the string the user just entered
            // requireActivity().hideKeyboard()
        }

        binding.sendButton.setOnClickListener {
            debug("User clicked sendButton")
            sendMessageInputText()
        }

        // max payload length should be 237 bytes but anything over 235 bytes crashes the radio
        binding.messageInputText.filters += Utf8ByteLengthFilter(234)

        binding.messageListView.setContent {
            val messages by model.getMessagesFrom(contactKey).collectAsStateWithLifecycle(listOf())

            AppTheme {
                if (messages.isNotEmpty()) {
                    MessageListView(
                        messages = messages,
                        selectedList = selectedList,
                        onClick = ::onClick,
                        onLongClick = ::onLongClick,
                        onChipClick = ::openNodeInfo,
                        onUnreadChanged = { model.clearUnreadCount(contactKey, it) },
                    )
                }
            }
        }

        // If connection state _OR_ myID changes we have to fix our ability to edit outgoing messages
        model.connectionState.observe(viewLifecycleOwner) {
            // If we don't know our node ID and we are offline don't let user try to send
            val isConnected = model.isConnected()
            binding.textInputLayout.isEnabled = isConnected
            binding.sendButton.isEnabled = isConnected
            for (subView: View in binding.quickChatLayout.allViews) {
                if (subView is Button) {
                    subView.isEnabled = isConnected
                }
            }
        }

        model.quickChatActions.asLiveData().observe(viewLifecycleOwner) { actions ->
            actions?.let {
                // This seems kinda hacky it might be better to replace with a recycler view
                binding.quickChatLayout.removeAllViews()
                for (action in actions) {
                    val button = Button(context)
                    button.text = action.name
                    button.isEnabled = model.isConnected()
                    if (action.mode == QuickChatAction.Mode.Instant) {
                        button.backgroundTintList =
                            ContextCompat.getColorStateList(requireActivity(), R.color.colorMyMsg)
                    }
                    button.setOnClickListener {
                        if (action.mode == QuickChatAction.Mode.Append) {
                            val originalText = binding.messageInputText.text ?: ""
                            val needsSpace =
                                !originalText.endsWith(' ') && originalText.isNotEmpty()
                            val newText = buildString {
                                append(originalText)
                                if (needsSpace) append(' ')
                                append(action.message)
                            }
                            binding.messageInputText.setText(newText)
                            binding.messageInputText.setSelection(newText.length)
                        } else {
                            model.sendMessage(action.message, contactKey)
                        }
                    }
                    binding.quickChatLayout.addView(button)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        actionMode?.finish()
        actionMode = null
        _binding = null
    }

    private inner class ActionModeCallback : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_messages, menu)
            menu.findItem(R.id.muteButton).isVisible = false
            mode.title = "1"
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.deleteButton -> {
                    val deleteMessagesString = resources.getQuantityString(
                        R.plurals.delete_messages,
                        selectedList.size,
                        selectedList.size
                    )
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(deleteMessagesString)
                        .setPositiveButton(getString(R.string.delete)) { _, _ ->
                            debug("User clicked deleteButton")
                            model.deleteMessages(selectedList.map { it.uuid })
                            mode.finish()
                        }
                        .setNeutralButton(R.string.cancel) { _, _ ->
                        }
                        .show()
                }
                R.id.selectAllButton -> lifecycleScope.launch {
                    model.getMessagesFrom(contactKey).firstOrNull()?.let { messages ->
                        if (selectedList.size == messages.size) {
                            // if all selected -> unselect all
                            selectedList.clear()
                            mode.finish()
                        } else {
                            // else --> select all
                            selectedList.clear()
                            selectedList.addAll(messages)
                        }
                        actionMode?.title = selectedList.size.toString()
                    }
                }

                R.id.resendButton -> lifecycleScope.launch {
                    debug("User clicked resendButton")
                    var resendText = ""
                    selectedList.forEach {
                        resendText = resendText + it.text + System.lineSeparator()
                    }
                    if (resendText != "") {
                        resendText = resendText.substring(0, resendText.length - 1)
                    }
                    binding.messageInputText.setText(resendText)
                    mode.finish()
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selectedList.clear()
            actionMode = null
        }
    }

    private fun openNodeInfo(msg: Message) = lifecycleScope.launch {
        model.nodeList.firstOrNull()?.find { it.user.id == msg.user.id }?.let { node ->
            parentFragmentManager.popBackStack()
            model.focusUserNode(node)
        }
    }
}
