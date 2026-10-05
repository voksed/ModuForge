package dev.moduforge.host.ui

import androidx.annotation.StringRes
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.module.ModuleState
import dev.moduforge.host.R
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.Sensitivity

/** Human-readable name of a capability. */
@get:StringRes
val Capability.titleRes: Int
    get() = when (this) {
        Capability.NETWORK_OUTBOUND -> R.string.cap_network_outbound
        Capability.FILE_SANDBOXED -> R.string.cap_file_sandboxed
        Capability.FILE_SHARED -> R.string.cap_file_shared
        Capability.CLIPBOARD -> R.string.cap_clipboard
        Capability.NOTIFICATIONS -> R.string.cap_notifications
        Capability.BACKGROUND_EXECUTION -> R.string.cap_background_execution
        Capability.AI_INFERENCE -> R.string.cap_ai_inference
        Capability.BOT_GATEWAY -> R.string.cap_bot_gateway
        Capability.DEVICE_INFO -> R.string.cap_device_info
        Capability.LOCAL_NETWORK_SCAN -> R.string.cap_local_network_scan
    }

/** What the capability gives the module and why that matters. */
@get:StringRes
val Capability.descriptionRes: Int
    get() = when (this) {
        Capability.NETWORK_OUTBOUND -> R.string.cap_network_outbound_desc
        Capability.FILE_SANDBOXED -> R.string.cap_file_sandboxed_desc
        Capability.FILE_SHARED -> R.string.cap_file_shared_desc
        Capability.CLIPBOARD -> R.string.cap_clipboard_desc
        Capability.NOTIFICATIONS -> R.string.cap_notifications_desc
        Capability.BACKGROUND_EXECUTION -> R.string.cap_background_execution_desc
        Capability.AI_INFERENCE -> R.string.cap_ai_inference_desc
        Capability.BOT_GATEWAY -> R.string.cap_bot_gateway_desc
        Capability.DEVICE_INFO -> R.string.cap_device_info_desc
        Capability.LOCAL_NETWORK_SCAN -> R.string.cap_local_network_scan_desc
    }

@get:StringRes
val Sensitivity.labelRes: Int
    get() = when (this) {
        Sensitivity.NORMAL -> R.string.sensitivity_normal
        Sensitivity.SENSITIVE -> R.string.sensitivity_sensitive
        Sensitivity.INTRUSIVE -> R.string.sensitivity_intrusive
    }

@get:StringRes
val ModuleState.labelRes: Int
    get() = when (this) {
        ModuleState.INSTALLED -> R.string.state_installed
        ModuleState.ENABLED -> R.string.state_enabled
        ModuleState.RUNNING -> R.string.state_running
    }

@get:StringRes
val AuditEventType.labelRes: Int
    get() = when (this) {
        AuditEventType.MODULE_INSTALLED -> R.string.audit_module_installed
        AuditEventType.MODULE_REJECTED -> R.string.audit_module_rejected
        AuditEventType.MODULE_UPDATED -> R.string.audit_module_updated
        AuditEventType.MODULE_ENABLED -> R.string.audit_module_enabled
        AuditEventType.MODULE_DISABLED -> R.string.audit_module_disabled
        AuditEventType.MODULE_STARTED -> R.string.audit_module_started
        AuditEventType.MODULE_STOPPED -> R.string.audit_module_stopped
        AuditEventType.MODULE_KILLED -> R.string.audit_module_killed
        AuditEventType.MODULE_CRASHED -> R.string.audit_module_crashed
        AuditEventType.MODULE_CALLBACK_FAILED -> R.string.audit_module_callback_failed
        AuditEventType.MODULE_UNINSTALLED -> R.string.audit_module_uninstalled
        AuditEventType.CAPABILITY_REQUESTED -> R.string.audit_capability_requested
        AuditEventType.CAPABILITY_GRANTED -> R.string.audit_capability_granted
        AuditEventType.CAPABILITY_DENIED -> R.string.audit_capability_denied
        AuditEventType.CAPABILITY_REVOKED -> R.string.audit_capability_revoked
        AuditEventType.TARGET_AUTHORIZED -> R.string.audit_target_authorized
        AuditEventType.NETWORK_CONNECTED -> R.string.audit_network_connected
        AuditEventType.NETWORK_REFUSED -> R.string.audit_network_refused
    }
