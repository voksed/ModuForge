package dev.moduforge.host.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.moduforge.core.audit.AuditLog
import dev.moduforge.core.module.ModuleLogSink
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.module.ModuleNotifier
import dev.moduforge.host.runtime.ModuleNotifications
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.module.ModuleRuntime
import dev.moduforge.core.module.ModuleUiSink
import dev.moduforge.host.runtime.ModuleUiStore
import dev.moduforge.host.runtime.ModuleLogs
import dev.moduforge.sandbox.KeystoreStorageCipher
import dev.moduforge.sandbox.ModuleInstaller
import dev.moduforge.sandbox.ModuleNetworkRelay
import dev.moduforge.sandbox.ModuleStorage
import dev.moduforge.sandbox.ModulePackageStore
import dev.moduforge.sandbox.SandboxModuleRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import dev.moduforge.core.permission.ConsentPrompter
import dev.moduforge.core.permission.DefaultPermissionBroker
import dev.moduforge.core.permission.GrantStore
import dev.moduforge.core.permission.PermissionBroker
import dev.moduforge.core.module.UserInputPrompter
import dev.moduforge.host.consent.ConsentCoordinator
import dev.moduforge.host.consent.InputCoordinator
import dev.moduforge.host.data.AuditDao
import dev.moduforge.host.data.GrantDao
import dev.moduforge.host.data.HostDatabase
import dev.moduforge.host.data.ModuleDao
import dev.moduforge.host.data.RoomAuditLog
import dev.moduforge.host.data.RoomGrantStore
import dev.moduforge.host.data.RoomModuleRegistry
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HostModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): HostDatabase =
        Room.databaseBuilder(context, HostDatabase::class.java, "host.db")
            .addMigrations(HostDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun moduleDao(database: HostDatabase): ModuleDao = database.moduleDao()

    @Provides
    fun grantDao(database: HostDatabase): GrantDao = database.grantDao()

    @Provides
    fun auditDao(database: HostDatabase): AuditDao = database.auditDao()

    @Provides
    @Singleton
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun packageStore(@ApplicationContext context: Context): ModulePackageStore =
        ModulePackageStore(File(context.filesDir, "modules"))

    @Provides
    @Singleton
    fun moduleRuntime(
        @ApplicationContext context: Context,
        packages: ModulePackageStore,
        broker: PermissionBroker,
        logs: ModuleLogSink,
        ui: ModuleUiSink,
        network: ModuleNetworkRelay,
        storage: ModuleStorage,
        notifier: ModuleNotifier,
        input: UserInputPrompter,
        scope: CoroutineScope,
    ): ModuleRuntime = SandboxModuleRuntime(context, packages, broker, logs, ui, network, storage, notifier, input, scope)

    @Provides
    @Singleton
    fun networkRelay(broker: PermissionBroker, audit: AuditLog): ModuleNetworkRelay = ModuleNetworkRelay(broker, audit)

    @Provides
    @Singleton
    fun moduleStorage(@ApplicationContext context: Context): ModuleStorage =
        ModuleStorage(File(context.filesDir, "module-data"), KeystoreStorageCipher())

    @Provides
    @Singleton
    fun moduleManager(
        registry: ModuleRegistry,
        grants: GrantStore,
        audit: AuditLog,
        runtime: ModuleRuntime,
        output: ModuleLogSink,
    ): ModuleManager = ModuleManager(registry, grants, audit, runtime, output)

    @Provides
    @Singleton
    fun moduleInstaller(
        store: ModulePackageStore,
        registry: ModuleRegistry,
        manager: ModuleManager,
        storage: ModuleStorage,
    ): ModuleInstaller = ModuleInstaller(store, registry, manager, storage)

    @Provides
    @Singleton
    fun permissionBroker(
        registry: ModuleRegistry,
        grants: GrantStore,
        prompter: ConsentPrompter,
        audit: AuditLog,
    ): PermissionBroker = DefaultPermissionBroker(registry, grants, prompter, audit)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HostBindings {

    @Binds
    abstract fun moduleRegistry(impl: RoomModuleRegistry): ModuleRegistry

    @Binds
    abstract fun grantStore(impl: RoomGrantStore): GrantStore

    @Binds
    abstract fun auditLog(impl: RoomAuditLog): AuditLog

    @Binds
    abstract fun consentPrompter(impl: ConsentCoordinator): ConsentPrompter

    @Binds
    abstract fun moduleLogSink(impl: ModuleLogs): ModuleLogSink

    @Binds
    abstract fun moduleUiSink(impl: ModuleUiStore): ModuleUiSink

    @Binds
    abstract fun moduleNotifier(impl: ModuleNotifications): ModuleNotifier

    @Binds
    abstract fun userInputPrompter(impl: InputCoordinator): UserInputPrompter
}
