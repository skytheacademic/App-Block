package com.appblock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appblock.R
import com.appblock.engine.UnlockCategory
import com.appblock.security.GeneratedKey
import com.appblock.security.LockKeys
import com.appblock.security.qrBitmap
import com.appblock.ui.theme.LampDimens
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * The key, and the two things you ever do with it: stash it once, then prove you still have it.
 *
 * The whole commitment rests on the stash actually leaving the phone, so the setup sheet spends its
 * space on that instruction rather than on reassurance — and says plainly that this screen can never
 * be shown again, because only a one-way hash is kept.
 */

@Composable
fun KeySetupSheet(
    onConfirm: (GeneratedKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val lamp = LocalLamp.current
    val generated = remember { LockKeys.generate() }
    val qr = remember(generated) { qrBitmap(generated.code, 640) }

    LampSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(phosphor(R.drawable.ic_ph_key), null, tint = lamp.neutral300, size = 17.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.key_title), style = LampType.sectionTitle, color = lamp.text)
        }

        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .align(Alignment.CenterHorizontally)
                .size(216.dp)
                .background(lamp.neutral900, RoundedCornerShape(LampDimens.cardRadius))
                .border(1.dp, lamp.divider, RoundedCornerShape(LampDimens.cardRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = stringResource(R.string.key_qr_description),
                modifier = Modifier.size(196.dp),
            )
        }

        SectionLabel(stringResource(R.string.key_code_label), Modifier.padding(top = 16.dp))
        Text(
            text = generated.code,
            style = LampType.monoCode,
            color = lamp.text,
            modifier = Modifier.padding(top = 6.dp),
        )

        FadingRule(Modifier.padding(top = 16.dp))
        Text(
            text = stringResource(R.string.key_stash_instruction),
            style = LampType.body,
            color = lamp.neutral400,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = stringResource(R.string.key_stash_detail),
            style = LampType.micro,
            color = lamp.neutral600,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LampButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                style = LampButtonStyle.Secondary,
                minHeight = 40.dp,
            )
            LampButton(
                text = stringResource(R.string.key_stashed_confirm),
                onClick = { onConfirm(generated) },
                modifier = Modifier.weight(1f),
                minHeight = 40.dp,
            )
        }
    }
}

/**
 * Start a wait. The camera path scans the stashed QR; typing it is the fallback.
 *
 * The hint that case and dashes don't matter is not a kindness — it is true of
 * [com.appblock.engine.KeyAuthority.normalize], which trims, strips dashes and spaces, and
 * uppercases before comparing. Saying so stops a correct code being read as a mismatch.
 */
@Composable
fun StartWindowSheet(
    category: UnlockCategory,
    verify: (String) -> Boolean,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lamp = LocalLamp.current
    var code by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    val websites = category == UnlockCategory.WEBSITES

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null) {
            code = scanned
            if (verify(scanned)) onVerified() else mismatch = true
        }
    }
    val scanPrompt = stringResource(R.string.key_scan_prompt)

    LampSheet(onDismiss = onDismiss) {
        Text(
            text = stringResource(
                if (websites) R.string.window_title_websites else R.string.window_title_apps,
            ),
            style = LampType.sectionTitle,
            color = lamp.text,
        )
        Text(
            text = stringResource(
                if (websites) R.string.window_body_websites else R.string.window_body_apps,
            ),
            style = LampType.body,
            color = lamp.neutral400,
            modifier = Modifier.padding(top = 8.dp),
        )
        LampButton(
            text = stringResource(R.string.key_scan),
            onClick = {
                scanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(scanPrompt)
                        .setBeepEnabled(false),
                )
            },
            leadingIcon = phosphor(R.drawable.ic_ph_camera),
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            minHeight = 48.dp,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(lamp.divider))
            Text(
                text = stringResource(R.string.key_or_type).uppercase(),
                style = LampType.kicker.copy(letterSpacing = LampType.sectionLabel.letterSpacing),
                color = lamp.neutral600,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Box(Modifier.weight(1f).height(1.dp).background(lamp.divider))
        }

        LampTextField(
            value = code,
            onValueChange = { code = it; mismatch = false },
            placeholder = stringResource(R.string.key_code_placeholder),
            textStyle = LampType.mono.copy(letterSpacing = LampType.monoCode.letterSpacing),
            modifier = Modifier.fillMaxWidth(),
        )
        // Mismatch and the format hint share one slot, so a wrong code replaces the instruction
        // instead of stacking a second line under it.
        Text(
            text = if (mismatch) {
                stringResource(R.string.key_mismatch)
            } else {
                stringResource(R.string.key_format_hint)
            },
            style = LampType.metaSmall,
            color = if (mismatch) lamp.accent300 else lamp.neutral600,
            modifier = Modifier.padding(top = 7.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LampButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                style = LampButtonStyle.Secondary,
                minHeight = 40.dp,
            )
            LampButton(
                text = stringResource(
                    if (websites) R.string.window_action_websites else R.string.window_action_apps,
                ),
                onClick = { if (verify(code)) onVerified() else mismatch = true },
                modifier = Modifier.weight(1f),
                minHeight = 40.dp,
            )
        }
    }
}
