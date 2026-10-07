// 在 HomeScreen（重命名、批量SNI）和 NodeEditScreen 中复用。

package com.xlink.android.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 单行文本输入对话框。
 *
 * @param title        对话框标题（对应 C 版 data->title）
 * @param prompt       提示文字（对应 C 版 data->prompt）
 * @param initialValue 初始文本（对应 C 版 data->buffer 预填充）
 * @param onConfirm    用户点击确定，返回输入内容（非空）
 * @param onDismiss    用户取消
 */
@Composable
fun InputDialog(
    title       : String,
    prompt      : String,
    initialValue: String  = "",
    placeholder : String  = "",
    onConfirm   : (String) -> Unit,
    onDismiss   : () -> Unit,
) {
    // 自动全选初始内容，方便用户直接覆写
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text      = initialValue,
                selection = TextRange(0, initialValue.length)
            )
        )
    }
    var errorMsg by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier      = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape         = MaterialTheme.shapes.large,
            elevation     = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 标题
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleLarge
                )

                // 提示文字
                if (prompt.isNotBlank()) {
                    Text(
                        text  = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // 输入框
                OutlinedTextField(
                    value           = textFieldValue,
                    onValueChange   = {
                        textFieldValue = it
                        if (errorMsg.isNotEmpty()) errorMsg = ""
                    },
                    modifier        = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine      = true,
                    placeholder     = { if (placeholder.isNotBlank()) Text(placeholder) },
                    isError         = errorMsg.isNotEmpty(),
                    supportingText  = if (errorMsg.isNotEmpty()) {
                        { Text(errorMsg, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val v = textFieldValue.text.trim()
                            if (v.isEmpty()) {
                                errorMsg = "输入不能为空"  // 对应 C 版 "输入不能为空" 提示
                            } else {
                                onConfirm(v)
                            }
                        }
                    )
                )

                // 按钮行
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment     = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val v = textFieldValue.text.trim()
                            if (v.isEmpty()) {
                                errorMsg = "输入不能为空"
                            } else {
                                onConfirm(v)
                            }
                        }
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }

    // 对话框打开后自动聚焦输入框（对应 C 版 SetFocus(GetDlgItem(h, ID_INPUT_EDIT))）
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/**
 * 确认对话框（二次确认，如删除节点）。
 * 对应 C 版 MessageBox(hwnd, "确定要删除...", MB_YESNO)。
 */
@Composable
fun ConfirmDialog(
    title     : String,
    message   : String,
    confirmText: String = "确定",
    cancelText : String = "取消",
    onConfirm : () -> Unit,
    onDismiss : () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title) },
        text             = { Text(message) },
        confirmButton    = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton    = {
            TextButton(onClick = onDismiss) { Text(cancelText) }
        }
    )
}
