package com.shapovalov.legalar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shapovalov.legalar.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), OverlayView.OnReferenceClickListener {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: MainViewModel by viewModels()

    private lateinit var analysisExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null

    private var imageAnalysis: ImageAnalysis? = null

    private var ocrAnalyzer: OcrAnalyzer? = null

    private var currentReferences: List<LegalReference> = emptyList()

    private var latestResult: ScanResult = ScanResult.empty()

    private var databaseSize: Int = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            binding.statusText.setText(R.string.camera_permission_denied)
            binding.foundText.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()
        binding.overlayView.onReferenceClick = this
        binding.foundText.setOnClickListener { showFoundReferences() }
        binding.toggleOverlay.setOnClickListener { viewModel.toggleOverlay() }
        binding.statusText.setText(R.string.status_scanning)

        observeState()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        viewModel.clearOverlay()
        super.onPause()
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        ocrAnalyzer?.shutdown()
        ocrAnalyzer = null
        imageAnalysis = null
        if (::analysisExecutor.isInitialized) {
            analysisExecutor.shutdown()
        }
        super.onDestroy()
    }

    override fun onReferenceClick(reference: LegalReference, sourceText: String) {
        showArticle(reference, sourceText)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.scanResult.collect { result ->
                        latestResult = result
                        binding.overlayView.submit(result)
                        renderStatus()
                    }
                }
                launch {
                    viewModel.catalog.collect { catalog ->
                        databaseSize = catalog.size
                        renderStatus()
                    }
                }
                launch {
                    viewModel.overlayEnabled.collect { enabled ->
                        binding.overlayView.overlayVisible = enabled
                        binding.toggleOverlay.setImageResource(
                            if (enabled) R.drawable.ic_overlay_on else R.drawable.ic_overlay_off
                        )
                        binding.toggleOverlay.contentDescription = getString(
                            if (enabled) R.string.overlay_disable else R.string.overlay_enable
                        )
                    }
                }
                launch {
                    viewModel.foundReferences.collect { references ->
                        currentReferences = references
                        if (references.isEmpty()) {
                            binding.foundText.visibility = View.GONE
                        } else {
                            binding.foundText.visibility = View.VISIBLE
                            binding.foundText.text = getString(
                                R.string.found_format,
                                references.size,
                                references.joinToString(LIST_SEPARATOR) { it.displayName() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun renderStatus() {
        binding.statusText.text = getString(
            R.string.status_format,
            latestResult.references.size,
            latestResult.blocks.size,
            databaseSize
        )
    }

    private fun showArticle(reference: LegalReference, sourceText: String) {
        val message = buildString {
            append(reference.title)
            append(DOUBLE_NEW_LINE)
            append(reference.fullText)
            append(DOUBLE_NEW_LINE)
            append(getString(R.string.dialog_deadline, reference.deadline))
            append(NEW_LINE)
            append(getString(R.string.dialog_risk, reference.risk))
            if (sourceText.isNotBlank()) {
                append(DOUBLE_NEW_LINE)
                append(getString(R.string.dialog_source, sourceText))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title, reference.code, reference.article))
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showFoundReferences() {
        val references = currentReferences
        if (references.isEmpty()) {
            return
        }
        val titles = references.map { reference ->
            (reference.displayName() + " — " + reference.title) as CharSequence
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.found_dialog_title)
            .setItems(titles) { _, which ->
                val index = which.coerceIn(0, references.lastIndex)
                showArticle(references[index], "")
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    cameraProvider = providerFuture.get()
                    bindCamera()
                } catch (error: Exception) {
                    binding.statusText.setText(R.string.camera_error)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val backCameraAvailable = try {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (error: CameraInfoUnavailableException) {
            false
        }
        if (!backCameraAvailable) {
            binding.statusText.setText(R.string.camera_error)
            return
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
        preview.setSurfaceProvider(binding.previewView.surfaceProvider)

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val analyzer = OcrAnalyzer(
            context = applicationContext,
            repository = viewModel.legalRepository,
            scope = lifecycleScope,
            onScanResult = { result -> viewModel.onScanResult(result) },
            onError = { message ->
                binding.statusText.post {
                    binding.statusText.text = message
                }
            }
        )
        analysis.setAnalyzer(analysisExecutor, analyzer)

        imageAnalysis = analysis
        ocrAnalyzer = analyzer

        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )
    }

    private companion object {
        const val ANALYSIS_WIDTH = 1280
        const val ANALYSIS_HEIGHT = 960
        const val NEW_LINE = "\n"
        const val DOUBLE_NEW_LINE = "\n\n"
        const val LIST_SEPARATOR = ", "
    }
}
