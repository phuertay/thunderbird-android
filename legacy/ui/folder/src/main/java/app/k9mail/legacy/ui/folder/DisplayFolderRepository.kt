package app.k9mail.legacy.ui.folder

import app.k9mail.core.mail.folder.api.Folder
import app.k9mail.core.mail.folder.api.FolderType
import app.k9mail.legacy.account.Account
import app.k9mail.legacy.account.Account.FolderMode
import app.k9mail.legacy.account.AccountManager
import app.k9mail.legacy.mailstore.FolderSettingsChangedListener
import app.k9mail.legacy.mailstore.FolderTypeMapper
import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.message.controller.MessagingControllerRegistry
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class DisplayFolderRepository(
    private val accountManager: AccountManager,
    private val messagingController: MessagingControllerRegistry,
    private val messageStoreManager: MessageStoreManager,
    private val coroutineContext: CoroutineContext = Dispatchers.IO,
) {
    private val sortForDisplay =
        compareByDescending<DisplayFolder> { it.folder.type == FolderType.INBOX }
            .thenByDescending { it.folder.type == FolderType.OUTBOX }
            .thenByDescending { it.folder.type != FolderType.REGULAR }
            .thenByDescending { it.isInTopGroup }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.folder.name }

    private fun getDisplayFolders(account: Account, displayMode: Account.FolderMode?): List<DisplayFolder> {
        val messageStore = messageStoreManager.getMessageStore(account.uuid)
        return messageStore.getDisplayFolders(
            displayMode = displayMode ?: account.folderDisplayMode,
            outboxFolderId = account.outboxFolderId,
        ) { folder ->
            DisplayFolder(
                folder = Folder(
                    id = folder.id,
                    name = folder.name,
                    type = FolderTypeMapper.folderTypeOf(account, folder.id),
                    isLocalOnly = folder.isLocalOnly,
                ),
                isInTopGroup = folder.isInTopGroup,
                unreadMessageCount = folder.unreadMessageCount,
                starredMessageCount = folder.starredMessageCount,
            )
        }.sortedWith(sortForDisplay)
    }

    fun getDisplayFoldersFlow(account: Account, includeHiddenFolders: Boolean): Flow<List<DisplayFolder>> {
        return if (includeHiddenFolders) {
            getDisplayFoldersFlow(account, FolderMode.ALL)
        } else {
            getDisplayFoldersFlow(account.uuid)
        }
    }

    private fun getDisplayFoldersFlow(account: Account, displayMode: Account.FolderMode): Flow<List<DisplayFolder>> {
        val messageStore = messageStoreManager.getMessageStore(account.uuid)

        return callbackFlow {
            send(getDisplayFolders(account, displayMode))

            val folderStatusChangedListener = object : SimpleMessagingListener() {
                override fun folderStatusChanged(statusChangedAccount: Account, folderId: Long) {
                    if (statusChangedAccount.uuid == account.uuid) {
                        trySendBlocking(getDisplayFolders(account, displayMode))
                    }
                }
            }
            messagingController.addListener(folderStatusChangedListener)

            val folderSettingsChangedListener = FolderSettingsChangedListener {
                trySendBlocking(getDisplayFolders(account, displayMode))
            }
            messageStore.addFolderSettingsChangedListener(folderSettingsChangedListener)

            awaitClose {
                messagingController.removeListener(folderStatusChangedListener)
                messageStore.removeFolderSettingsChangedListener(folderSettingsChangedListener)
            }
        }.buffer(capacity = Channel.CONFLATED)
            .distinctUntilChanged()
            .flowOn(coroutineContext)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getDisplayFoldersFlow(accountUuid: String): Flow<List<DisplayFolder>> {
        return accountManager.getAccountFlow(accountUuid)
            .map { latestAccount ->
                AccountContainer(latestAccount, latestAccount.folderDisplayMode)
            }
            .distinctUntilChanged()
            .flatMapLatest { (account, folderDisplayMode) ->
                getDisplayFoldersFlow(account, folderDisplayMode)
            }
    }
}

private data class AccountContainer(
    val account: Account,
    val folderDisplayMode: FolderMode,
)