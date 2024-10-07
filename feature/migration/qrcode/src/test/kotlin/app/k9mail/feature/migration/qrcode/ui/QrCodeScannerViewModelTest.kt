package app.k9mail.feature.migration.qrcode.ui

import android.app.Application
import app.k9mail.core.ui.compose.testing.MainDispatcherRule
import app.k9mail.core.ui.compose.testing.mvi.MviTurbines
import app.k9mail.core.ui.compose.testing.mvi.turbinesWithInitialStateCheck
import app.k9mail.feature.migration.qrcode.domain.QrCodeDomainContract.UseCase
import app.k9mail.feature.migration.qrcode.domain.usecase.QrCodePayloadReader
import app.k9mail.feature.migration.qrcode.ui.QrCodeScannerContract.Effect
import app.k9mail.feature.migration.qrcode.ui.QrCodeScannerContract.Event
import app.k9mail.feature.migration.qrcode.ui.QrCodeScannerContract.State
import app.k9mail.feature.migration.qrcode.ui.QrCodeScannerContract.UiPermissionState
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QrCodeScannerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `user grants camera permission`() = runTest {
        with(QrCodeScannerScreenRobot(testScope = this)) {
            startScreen()
            userGrantsCameraPermission()

            ensureThatAllEventsAreConsumed()
        }
    }

    @Test
    fun `user denies camera permission`() = runTest {
        with(QrCodeScannerScreenRobot(testScope = this)) {
            startScreen()
            userDeniesCameraPermission()

            ensureThatAllEventsAreConsumed()
        }
    }

    @Test
    fun `user grants camera permission via android app settings`() = runTest {
        with(QrCodeScannerScreenRobot(testScope = this)) {
            startScreen()
            systemDeniesCameraPermission()

            userClicksGoToSettings()
            userReturnsFromAppInfoScreen()
            systemGrantsCameraPermission()

            ensureThatAllEventsAreConsumed()
        }
    }

    @Test
    fun `user scans QR code`() = runTest {
        with(QrCodeScannerScreenRobot(testScope = this)) {
            startScreen()
            systemGrantsCameraPermission()

            userScansQrCode(sequenceNumber = 1, sequenceEnd = 2)
            assertScannedStatus(expectedScannedCount = 1, expectedScannedTotal = 2)

            userScansQrCode(sequenceNumber = 2, sequenceEnd = 2)
            assertScannedStatus(expectedScannedCount = 2, expectedScannedTotal = 2)

            assertSettingsWritten(expectedNumberOfAccounts = 2)

            ensureThatAllEventsAreConsumed()
        }
    }
}

private class QrCodeScannerScreenRobot(
    private val testScope: TestScope,
) {
    private val qrCodeSettingsWriter = FakeQrCodeSettingsWriter()
    private val viewModel = QrCodeScannerViewModel(
        qrCodePayloadReader = QrCodePayloadReader(),
        qrCodeSettingsWriter = qrCodeSettingsWriter,
        createCameraUseCaseProvider = { listener ->
            qrCodeListener = listener
            UseCase.CameraUseCasesProvider { emptyList() }
        },
        backgroundDispatcher = Dispatchers.Unconfined,
    )
    private lateinit var qrCodeListener: (String) -> Unit
    private lateinit var turbines: MviTurbines<State, Effect>

    private val initialState = State()

    suspend fun startScreen() {
        turbines = testScope.turbinesWithInitialStateCheck(viewModel, initialState)

        viewModel.event(Event.StartScreen)

        assertThat(turbines.awaitEffectItem()).isEqualTo(Effect.RequestCameraPermission)
    }

    suspend fun userGrantsCameraPermission() {
        grantCameraPermission()
    }

    suspend fun systemGrantsCameraPermission() {
        grantCameraPermission()
    }

    private suspend fun grantCameraPermission() {
        viewModel.event(Event.CameraPermissionResult(success = true))

        assertThat(turbines.awaitStateItem()).isEqualTo(State(cameraPermissionState = UiPermissionState.Granted))
    }

    suspend fun userDeniesCameraPermission() {
        denyCameraPermission()
    }

    suspend fun systemDeniesCameraPermission() {
        denyCameraPermission()
    }

    private suspend fun denyCameraPermission() {
        viewModel.event(Event.CameraPermissionResult(success = false))

        assertThat(turbines.awaitStateItem()).isEqualTo(State(cameraPermissionState = UiPermissionState.Denied))
    }

    suspend fun userClicksGoToSettings() {
        viewModel.event(Event.GoToSettingsClicked)

        assertThat(turbines.awaitEffectItem()).isEqualTo(Effect.GoToAppInfoScreen)
        assertThat(turbines.awaitStateItem()).isEqualTo(State(cameraPermissionState = UiPermissionState.Waiting))
    }

    suspend fun userReturnsFromAppInfoScreen() {
        viewModel.event(Event.ReturnedFromAppInfoScreen)

        assertThat(turbines.awaitStateItem()).isEqualTo(State(cameraPermissionState = UiPermissionState.Unknown))
        assertThat(turbines.awaitEffectItem()).isEqualTo(Effect.RequestCameraPermission)
    }

    fun userScansQrCode(sequenceNumber: Int, sequenceEnd: Int) {
        val payload = """[1,[$sequenceNumber,$sequenceEnd],""" +
            """[0,"imap.domain.example",993,3,2,"username","My Account"],""" +
            """[[[0,"smtp.domain.example",587,2,1,"username"],""" +
            """["user@domain.example","Firstname Lastname"]]]]"""

        qrCodeListener.invoke(payload)
    }

    suspend fun assertScannedStatus(expectedScannedCount: Int, expectedScannedTotal: Int) {
        assertThat(turbines.awaitStateItem()).isEqualTo(
            State(
                cameraPermissionState = UiPermissionState.Granted,
                scannedCount = expectedScannedCount,
                totalCount = expectedScannedTotal,
            ),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun assertSettingsWritten(expectedNumberOfAccounts: Int) {
        testScope.advanceUntilIdle()

        assertThat(qrCodeSettingsWriter.arguments).isNotNull().hasSize(expectedNumberOfAccounts)
    }

    fun ensureThatAllEventsAreConsumed() {
        turbines.effectTurbine.ensureAllEventsConsumed()
        turbines.stateTurbine.ensureAllEventsConsumed()
    }
}