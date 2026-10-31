package com.x8bit.bitwarden.ui.vault.feature.addedit

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitwarden.ui.platform.base.util.cardStyle
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.button.BitwardenOutlinedButton
import com.bitwarden.ui.platform.components.button.BitwardenStandardIconButton
import com.bitwarden.ui.platform.components.header.BitwardenListHeaderText
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.ui.vault.feature.addedit.handlers.VaultAddEditCommonHandlers

/**
 * The attachments section of the add/edit screen: files picked here are encrypted and attached
 * when the item is saved.
 */
fun LazyListScope.vaultAddEditAttachments(
    commonState: VaultAddEditState.ViewState.Content.Common,
    commonTypeHandlers: VaultAddEditCommonHandlers,
) {
    item {
        Spacer(modifier = Modifier.height(height = 16.dp))
        BitwardenListHeaderText(
            label = stringResource(id = BitwardenString.attachments),
            modifier = Modifier
                .fillMaxWidth()
                .standardHorizontalMargin()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(height = 8.dp))
    }

    itemsIndexed(commonState.pendingAttachments) { index, attachment ->
        val context = LocalContext.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .testTag(tag = "PendingAttachmentRow")
                .standardHorizontalMargin()
                .fillMaxWidth()
                .cardStyle(
                    cardStyle = if (index == 0) CardStyle.Top() else CardStyle.Middle(),
                    paddingStart = 16.dp,
                    paddingEnd = 4.dp,
                ),
        ) {
            Column(modifier = Modifier.weight(weight = 1f)) {
                Text(
                    text = attachment.fileName,
                    style = BitwardenTheme.typography.bodyLarge,
                    color = BitwardenTheme.colorScheme.text.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Formatter.formatShortFileSize(context, attachment.sizeBytes),
                    style = BitwardenTheme.typography.bodySmall,
                    color = BitwardenTheme.colorScheme.text.secondary,
                )
            }
            BitwardenStandardIconButton(
                vectorIconRes = BitwardenDrawable.ic_close,
                contentDescription = stringResource(id = BitwardenString.remove),
                onClick = { commonTypeHandlers.onRemovePendingAttachmentClick(attachment) },
                modifier = Modifier.testTag(tag = "RemovePendingAttachmentButton"),
            )
        }
    }

    item {
        BitwardenOutlinedButton(
            label = stringResource(id = BitwardenString.add_file),
            onClick = commonTypeHandlers.onAddAttachmentClick,
            icon = null,
            cardStyle = if (commonState.pendingAttachments.isEmpty()) {
                CardStyle.Full
            } else {
                CardStyle.Bottom
            },
            modifier = Modifier
                .testTag(tag = "AddAttachmentButton")
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )
    }
}
