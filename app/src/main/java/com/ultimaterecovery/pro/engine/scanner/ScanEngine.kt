package com.ultimaterecovery.pro.engine.scanner

import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import com.ultimaterecovery.pro.engine.recovery.FoundFileInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * محرك المسح الرئيسي - قلب تطبيق الاستعادة
 *
 * هذه الفئة هي نقطة الدخول الرئيسية لجميع عمليات المسح.
 * تدير دورة حياة المسح بالكامل من البداية حتى الانتهاء،
 * وتدعم أنواعاً متعددة من المسح:
 *
 * 1. المسح السريع (Quick Scan): يبحث في نظام الملفات عن إدخالات محذوفة
 * 2. المسح العميق (Deep Scan): يقرأ الكتل الخام من القرص عند توفر صلاحيات الروت
 * 3. مسح التوقيعات (Signature Scan): يبحث عن توقيعات الملفات في المساحة غير المخصصة
 * 4. المسح الخام (Raw Scan): مسح قسم كامل بايت ببايت
 * 5. مسح الأقسام (Partition Scan): اكتشاف ومسح أقسام التخزين
 *
 * يدعم المحرك:
 * - الإيقاف المؤقت والاستئناف
 * - الإلغاء الكامل
 * - تتبع التقدم في الوقت الفعلي
 * - تقارير الحالة المستمرة عبر Flow
 */
/**
 * الحالات الممكنة لعملية المسح
 *
 * تستخدم كحالة مغلقة (Sealed Class) لضمان تغطية جميع الحالات
 * في كود العرض (UI) ومنع الحالات غير المتوقعة.
 */
sealed class ScanState {

    /** حالة الخمول - لا يوجد مسح جارٍ */
    data object Idle : ScanState()

    /**
     * حالة المسح النشط
     *
     * @property progress نسبة التقدم من 0.0 إلى 1.0
     * @property currentPath المسار الحالي قيد المسح
     * @property filesFound عدد الملفات المكتشفة حتى الآن
     * @property bytesScanned عدد البايتات التي تم مسحها
     * @property totalBytes إجمالي البايتات المطلوب مسحها (0 إذا غير معروف)
     * @property scanType نوع المسح الحالي
     * @property elapsedMs الوقت المنقضي بالمللي ثانية
     */
    data class Scanning(
        val progress: Float = 0f,
        val currentPath: String = "",
        val filesFound: Int = 0,
        val bytesScanned: Long = 0L,
        val totalBytes: Long = 0L,
        val scanType: ScanType = ScanType.QUICK,
        val elapsedMs: Long = 0L
    ) : ScanState()

    /**
     * حالة الإيقاف المؤقت
     *
     * @property filesFound عدد الملفات المكتشفة قبل الإيقاف
     * @property progress نسبة التقدم عند الإيقاف
     * @property pausedPath المسار الذي تم الإيقاف عنده
     */
    data class Paused(
        val filesFound: Int = 0,
        val progress: Float = 0f,
        val pausedPath: String = ""
    ) : ScanState()

    /**
     * حالة الانتهاء الناجح
     *
     * @property results قائمة الملفات المكتشفة
     * @property totalFiles إجمالي عدد الملفات
     * @property totalSize إجمالي حجم الملفات بالبايت
     * @property durationMs مدة المسح بالمللي ثانية
     * @property scanType نوع المسح المنجز
     */
    data class Completed(
        val results: List<FoundFileInfo> = emptyList(),
        val totalFiles: Int = 0,
        val totalSize: Long = 0L,
        val durationMs: Long = 0L,
        val scanType: ScanType = ScanType.QUICK
    ) : ScanState()

    /**
     * حالة الفشل
     *
     * @property error رسالة الخطأ
     * @property scanType نوع المسح الذي فشل
     * @property partialResults نتائج جزئية تم جمعها قبل الفشل
     */
    data class Failed(
        val error: String,
        val scanType: ScanType = ScanType.QUICK,
        val partialResults: List<FoundFileInfo> = emptyList()
    ) : ScanState()

    /** حالة الإلغاء - تم إلغاء المسح بواسطة المستخدم */
    data object Cancelled : ScanState()
}

/**
 * واجهة محرك المسح
 *
 * تحدد جميع العمليات المتاحة للمسح والاستعادة.
 * يمكن استخدام هذه الواجهة للحقن والاختبار.
 */
interface IScanEngine {

    /**
     * بدء مسح سريع
     *
     * يبحث في نظام الملفات عن الملفات المحذوفة في المسارات المحددة.
     * أسرع أنواع المسح لكنه يعتمد على وجود إدخالات الملفات.
     *
     * @param paths قائمة المسارات المراد مسحها
     * @param categories فئات الملفات المراد البحث عنها (فارغ = جميع الفئات)
     * @return تدفق حالة المسح
     */
    fun startQuickScan(paths: List<String>, categories: List<FileCategory> = emptyList()): Flow<ScanState>

    /**
     * بدء مسح عميق
     *
     * يقرأ الكتل الخام من التخزين ويبحث عن توقيعات الملفات.
     * أبطأ من المسح السريع لكنه يجد ملفات أكثر.
     * يحتاج صلاحيات الروت للوصول المباشر للقرص.
     *
     * @param paths قائمة المسارات أو الأقسام المراد مسحها
     * @param categories فئات الملفات المراد البحث عنها
     * @return تدفق حالة المسح
     */
    fun startDeepScan(paths: List<String>, categories: List<FileCategory> = emptyList()): Flow<ScanState>

    /**
     * بدء مسح بالتوقيعات
     *
     * يبحث عن توقيعات الملفات (Magic Numbers) في البيانات الخام.
     * مناسب لاستعادة الملفات التي تم حذف إدخالاتها من نظام الملفات.
     *
     * @param paths قائمة المسارات المراد مسحها
     * @return تدفق حالة المسح
     */
    fun startSignatureScan(paths: List<String>): Flow<ScanState>

    /**
     * بدء مسح خام لقسم محدد
     *
     * يقرأ بيانات القسم بايت ببايت ويبحث عن توقيعات الملفات.
     * يتطلب صلاحيات الروت ومسار جهاز القسم (مثل /dev/block/sda1).
     *
     * @param partitionPath مسار جهاز القسم
     * @return تدفق حالة المسح
     */
    fun startRawScan(partitionPath: String): Flow<ScanState>

    /**
     * بدء مسح جميع أقسام التخزين
     *
     * يكتشف جميع أقسام التخزين المتاحة ويبدأ مسحها.
     * يتطلب صلاحيات الروت.
     *
     * @return تدفق حالة المسح
     */
    fun startPartitionScan(): Flow<ScanState>

    /** إيقاف المسح مؤقتاً */
    fun pauseScan()

    /** استئناف المسح بعد الإيقاف المؤقت */
    fun resumeScan()

    /** إلغاء المسح بالكامل */
    fun cancelScan()

    /** الحصول على حالة المسح الحالية */
    fun getScanState(): StateFlow<ScanState>
}

/**
 * تنفيذ محرك المسح الرئيسي
 *
 * ينسق بين الماسحات المختلفة (سريع، عميق، توقيعات، خام)
 * ويدير دورة حياة المسح. يستخدم Kotlin Coroutines للعمليات
 * غير المتزامنة و StateFlow لتحديثات الحالة في الوقت الفعلي.
 */
@Singleton
class ScanEngine @Inject constructor(
    private val quickScanner: QuickScanner,
    private val deepScanner: DeepScanner,
    private val signatureScanner: SignatureScanner,
    private val mediaScanner: MediaScanner
) : IScanEngine {

    // ──────────────────────────────────────────────
    // إدارة الحالة
    // ──────────────────────────────────────────────

    /** حالة المسح الحالية - يمكن ملاحظتها من واجهة المستخدم */
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    override fun getScanState(): StateFlow<ScanState> = _scanState.asStateFlow()

    /** نطاق الكوروتين الخاص بعمليات المسح */
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** مهمة المسح الحالية - تستخدم للإلغاء */
    private var currentScanJob: Job? = null

    /** علامة الإيقاف المؤقت */
    @Volatile
    private var isPaused = false

    /** علامة الإلغاء */
    @Volatile
    private var isCancelled = false

    /** قفل للتزامن بين الإيقاف/الاستئناف */
    private val pauseLock = Object()

    /** دالة التنظيف - يجب استدعاؤها عند عدم الحاجة للمحرك */
    fun cleanup() {
        currentScanJob?.cancel()
        currentScanJob = null
        scanScope.cancel()
    }

    // ──────────────────────────────────────────────
    // عمليات المسح
    // ──────────────────────────────────────────────

    /**
     * بدء مسح سريع
     *
     * يعمل على مسح المسارات المحددة بحثاً عن ملفات محذوفة
     * باستخدام QuickScanner الذي يفحص نظام الملفات والذاكرة المؤقتة
     */
    override fun startQuickScan(
        paths: List<String>,
        categories: List<FileCategory>
    ): Flow<ScanState> = channelFlow {
        // التحقق من عدم وجود مسح جارٍ
        if (_scanState.value is ScanState.Scanning) {
            send(ScanState.Failed("يوجد مسح جارٍ بالفعل", ScanType.QUICK))
            return@channelFlow
        }

        resetScanState()
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()

        _scanState.value = ScanState.Scanning(scanType = ScanType.QUICK)
        send(ScanState.Scanning(scanType = ScanType.QUICK))

        try {
            quickScanner.scan(paths, categories)
                .collect { state ->
                    // فحص الإيقاف المؤقت
                    checkPauseState()

                    // فحص الإلغاء
                    if (isCancelled) {
                        _scanState.value = ScanState.Cancelled
                        send(ScanState.Cancelled)
                        return@collect
                    }

                    when (state) {
                        is ScanState.Scanning -> {
                            foundFiles.clear()
                            _scanState.value = state
                            send(state)
                        }
                        is ScanState.Completed -> {
                            foundFiles.addAll(state.results)
                            val elapsed = System.currentTimeMillis() - startTime
                            val completed = ScanState.Completed(
                                results = state.results,
                                totalFiles = state.totalFiles,
                                totalSize = state.totalSize,
                                durationMs = elapsed,
                                scanType = ScanType.QUICK
                            )
                            _scanState.value = completed
                            send(completed)
                        }
                        is ScanState.Failed -> {
                            _scanState.value = state
                            send(state)
                        }
                        else -> {
                            _scanState.value = state
                            send(state)
                        }
                    }
                }
        } catch (e: CancellationException) {
            _scanState.value = ScanState.Cancelled
            send(ScanState.Cancelled)
        } catch (e: Exception) {
            val failed = ScanState.Failed(
                error = e.message ?: "خطأ غير معروف في المسح السريع",
                scanType = ScanType.QUICK,
                partialResults = foundFiles
            )
            _scanState.value = failed
            send(failed)
        }
    }

    /**
     * بدء مسح عميق
     *
     * يقرأ الكتل الخام من التخزين باستخدام DeepScanner
     * يتطلب صلاحيات الروت عادةً
     */
    override fun startDeepScan(
        paths: List<String>,
        categories: List<FileCategory>
    ): Flow<ScanState> = channelFlow {
        if (_scanState.value is ScanState.Scanning) {
            send(ScanState.Failed("يوجد مسح جارٍ بالفعل", ScanType.DEEP))
            return@channelFlow
        }

        resetScanState()
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()

        _scanState.value = ScanState.Scanning(scanType = ScanType.DEEP)
        send(ScanState.Scanning(scanType = ScanType.DEEP))

        try {
            deepScanner.scan(paths, categories)
                .collect { state ->
                    checkPauseState()

                    if (isCancelled) {
                        _scanState.value = ScanState.Cancelled
                        send(ScanState.Cancelled)
                        return@collect
                    }

                    when (state) {
                        is ScanState.Scanning -> {
                            _scanState.value = state.copy(scanType = ScanType.DEEP)
                            send(state.copy(scanType = ScanType.DEEP))
                        }
                        is ScanState.Completed -> {
                            foundFiles.addAll(state.results)
                            val elapsed = System.currentTimeMillis() - startTime
                            val completed = ScanState.Completed(
                                results = state.results,
                                totalFiles = state.totalFiles,
                                totalSize = state.totalSize,
                                durationMs = elapsed,
                                scanType = ScanType.DEEP
                            )
                            _scanState.value = completed
                            send(completed)
                        }
                        is ScanState.Failed -> {
                            _scanState.value = state
                            send(state)
                        }
                        else -> {
                            _scanState.value = state
                            send(state)
                        }
                    }
                }
        } catch (e: CancellationException) {
            _scanState.value = ScanState.Cancelled
            send(ScanState.Cancelled)
        } catch (e: Exception) {
            val failed = ScanState.Failed(
                error = e.message ?: "خطأ غير معروف في المسح العميق",
                scanType = ScanType.DEEP,
                partialResults = foundFiles
            )
            _scanState.value = failed
            send(failed)
        }
    }

    /**
     * بدء مسح بالتوقيعات
     *
     * يستخدم SignatureScanner للبحث عن توقيعات الملفات
     * في البيانات الخام بدون الاعتماد على نظام الملفات
     */
    override fun startSignatureScan(paths: List<String>): Flow<ScanState> = channelFlow {
        if (_scanState.value is ScanState.Scanning) {
            send(ScanState.Failed("يوجد مسح جارٍ بالفعل", ScanType.SIGNATURE))
            return@channelFlow
        }

        resetScanState()
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()

        _scanState.value = ScanState.Scanning(scanType = ScanType.SIGNATURE)
        send(ScanState.Scanning(scanType = ScanType.SIGNATURE))

        try {
            signatureScanner.scanBySignatures(paths)
                .collect { state ->
                    checkPauseState()

                    if (isCancelled) {
                        _scanState.value = ScanState.Cancelled
                        send(ScanState.Cancelled)
                        return@collect
                    }

                    when (state) {
                        is ScanState.Scanning -> {
                            _scanState.value = state.copy(scanType = ScanType.SIGNATURE)
                            send(state.copy(scanType = ScanType.SIGNATURE))
                        }
                        is ScanState.Completed -> {
                            foundFiles.addAll(state.results)
                            val elapsed = System.currentTimeMillis() - startTime
                            val completed = ScanState.Completed(
                                results = state.results,
                                totalFiles = state.totalFiles,
                                totalSize = state.totalSize,
                                durationMs = elapsed,
                                scanType = ScanType.SIGNATURE
                            )
                            _scanState.value = completed
                            send(completed)
                        }
                        is ScanState.Failed -> {
                            _scanState.value = state
                            send(state)
                        }
                        else -> {
                            _scanState.value = state
                            send(state)
                        }
                    }
                }
        } catch (e: CancellationException) {
            _scanState.value = ScanState.Cancelled
            send(ScanState.Cancelled)
        } catch (e: Exception) {
            val failed = ScanState.Failed(
                error = e.message ?: "خطأ غير معروف في مسح التوقيعات",
                scanType = ScanType.SIGNATURE,
                partialResults = foundFiles
            )
            _scanState.value = failed
            send(failed)
        }
    }

    /**
     * بدء مسح خام لقسم محدد
     *
     * يقرأ القسم المحدد بايت ببايت ويبحث عن توقيعات الملفات
     * يتطلب صلاحيات الروت ومسار جهاز القسم
     */
    override fun startRawScan(partitionPath: String): Flow<ScanState> = channelFlow {
        if (_scanState.value is ScanState.Scanning) {
            send(ScanState.Failed("يوجد مسح جارٍ بالفعل", ScanType.RAW))
            return@channelFlow
        }

        resetScanState()
        val startTime = System.currentTimeMillis()
        val foundFiles = mutableListOf<FoundFileInfo>()

        _scanState.value = ScanState.Scanning(
            currentPath = partitionPath,
            scanType = ScanType.RAW
        )
        send(ScanState.Scanning(currentPath = partitionPath, scanType = ScanType.RAW))

        try {
            signatureScanner.scanRawPartition(partitionPath)
                .collect { state ->
                    checkPauseState()

                    if (isCancelled) {
                        _scanState.value = ScanState.Cancelled
                        send(ScanState.Cancelled)
                        return@collect
                    }

                    when (state) {
                        is ScanState.Scanning -> {
                            _scanState.value = state.copy(scanType = ScanType.RAW)
                            send(state.copy(scanType = ScanType.RAW))
                        }
                        is ScanState.Completed -> {
                            foundFiles.addAll(state.results)
                            val elapsed = System.currentTimeMillis() - startTime
                            val completed = ScanState.Completed(
                                results = state.results,
                                totalFiles = state.totalFiles,
                                totalSize = state.totalSize,
                                durationMs = elapsed,
                                scanType = ScanType.RAW
                            )
                            _scanState.value = completed
                            send(completed)
                        }
                        is ScanState.Failed -> {
                            _scanState.value = state
                            send(state)
                        }
                        else -> {
                            _scanState.value = state
                            send(state)
                        }
                    }
                }
        } catch (e: CancellationException) {
            _scanState.value = ScanState.Cancelled
            send(ScanState.Cancelled)
        } catch (e: Exception) {
            val failed = ScanState.Failed(
                error = e.message ?: "خطأ غير معروف في المسح الخام",
                scanType = ScanType.RAW,
                partialResults = foundFiles
            )
            _scanState.value = failed
            send(failed)
        }
    }

    /**
     * بدء مسح جميع أقسام التخزين
     *
     * يكتشف جميع أقسام التخزين المتاحة (يتطلب روت)
     * ثم يبدأ مسح كل قسم بالتتابع
     */
    override fun startPartitionScan(): Flow<ScanState> = channelFlow {
        if (_scanState.value is ScanState.Scanning) {
            send(ScanState.Failed("يوجد مسح جارٍ بالفعل", ScanType.PARTITION))
            return@channelFlow
        }

        resetScanState()
        val startTime = System.currentTimeMillis()
        val allFoundFiles = mutableListOf<FoundFileInfo>()

        _scanState.value = ScanState.Scanning(scanType = ScanType.PARTITION)
        send(ScanState.Scanning(scanType = ScanType.PARTITION))

        try {
            // اكتشاف أقسام التخزين المتاحة
            val partitions = discoverPartitions()

            if (partitions.isEmpty()) {
                val failed = ScanState.Failed(
                    error = "لم يتم العثور على أقسام تخزين. تأكد من صلاحيات الروت.",
                    scanType = ScanType.PARTITION
                )
                _scanState.value = failed
                send(failed)
                return@channelFlow
            }

            // مسح كل قسم بالتتابع
            for ((index, partition) in partitions.withIndex()) {
                if (isCancelled) break

                checkPauseState()

                // تحديث الحالة بمعلومات القسم الحالي
                val partitionProgress = (index.toFloat() / partitions.size)
                _scanState.value = ScanState.Scanning(
                    progress = partitionProgress,
                    currentPath = partition,
                    scanType = ScanType.PARTITION
                )
                send(ScanState.Scanning(
                    progress = partitionProgress,
                    currentPath = partition,
                    scanType = ScanType.PARTITION
                ))

                // مسح القسم الحالي
                signatureScanner.scanRawPartition(partition)
                    .collect { state ->
                        checkPauseState()
                        if (isCancelled) return@collect

                        when (state) {
                            is ScanState.Scanning -> {
                                // دمج تقدم القسم مع التقدم الإجمالي
                                val combinedProgress = (index.toFloat() + state.progress) / partitions.size
                                val combinedState = ScanState.Scanning(
                                    progress = combinedProgress,
                                    currentPath = state.currentPath.ifBlank { partition },
                                    filesFound = allFoundFiles.size + state.filesFound,
                                    bytesScanned = state.bytesScanned,
                                    totalBytes = state.totalBytes,
                                    scanType = ScanType.PARTITION
                                )
                                _scanState.value = combinedState
                                send(combinedState)
                            }
                            is ScanState.Completed -> {
                                allFoundFiles.addAll(state.results)
                            }
                            is ScanState.Failed -> {
                                // لا نتوقف عند فشل قسم واحد - نتابع مع البقية
                            }
                            else -> {}
                        }
                    }
            }

            // إرسال النتيجة النهائية
            val elapsed = System.currentTimeMillis() - startTime
            val completed = ScanState.Completed(
                results = allFoundFiles.toList(),
                totalFiles = allFoundFiles.size,
                totalSize = allFoundFiles.sumOf { it.fileSize },
                durationMs = elapsed,
                scanType = ScanType.PARTITION
            )
            _scanState.value = completed
            send(completed)

        } catch (e: CancellationException) {
            _scanState.value = ScanState.Cancelled
            send(ScanState.Cancelled)
        } catch (e: Exception) {
            val failed = ScanState.Failed(
                error = e.message ?: "خطأ غير معروف في مسح الأقسام",
                scanType = ScanType.PARTITION,
                partialResults = allFoundFiles
            )
            _scanState.value = failed
            send(failed)
        }
    }

    // ──────────────────────────────────────────────
    // التحكم في المسح
    // ──────────────────────────────────────────────

    /**
     * إيقاف المسح مؤقتاً
     *
     * يوقف المسح عند نقطة التحقق التالية دون فقدان التقدم.
     * يمكن استئنافه لاحقاً باستخدام resumeScan()
     */
    override fun pauseScan() {
        if (_scanState.value !is ScanState.Scanning) return
        isPaused = true

        val currentState = _scanState.value
        if (currentState is ScanState.Scanning) {
            _scanState.value = ScanState.Paused(
                filesFound = currentState.filesFound,
                progress = currentState.progress,
                pausedPath = currentState.currentPath
            )
        }
    }

    /**
     * استئناف المسح بعد الإيقاف المؤقت
     *
     * يُعلم المسح بأن يمكنه المتابعة من حيث توقف
     */
    override fun resumeScan() {
        if (!isPaused) return
        isPaused = false

        // إيقاظ أي خيط ينتظر في checkPauseState()
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }

        // تحديث الحالة للعودة إلى المسح
        val pausedState = _scanState.value
        if (pausedState is ScanState.Paused) {
            _scanState.value = ScanState.Scanning(
                progress = pausedState.progress,
                currentPath = pausedState.pausedPath,
                filesFound = pausedState.filesFound
            )
        }
    }

    /**
     * إلغاء المسح بالكامل
     *
     * يلغي عملية المسح فوراً ويعيد الموارد
     */
    override fun cancelScan() {
        isCancelled = true
        isPaused = false

        // إيقاظ أي خيط ينتظر الإيقاف المؤقت
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }

        // إلغاء مهمة المسح الحالية
        currentScanJob?.cancel()
        currentScanJob = null

        _scanState.value = ScanState.Cancelled
    }

    // ──────────────────────────────────────────────
    // دوال مساعدة داخلية
    // ──────────────────────────────────────────────

    /**
     * إعادة تعيين حالة المسح لبدء مسح جديد
     */
    private fun resetScanState() {
        isPaused = false
        isCancelled = false
        currentScanJob?.cancel()
        currentScanJob = null
    }

    /**
     * فحص حالة الإيقاف المؤقت
     *
     * إذا كان المسح متوقفاً مؤقتاً، يدخل الخيط في حالة انتظار
     * حتى يتم استئنافه أو إلغاؤه
     *
     * @throws CancellationException إذا تم إلغاء المسح أثناء الانتظار
     */
    private suspend fun checkPauseState() {
        while (isPaused && !isCancelled) {
            delay(500) // استخدام delay بدلاً من wait في الكوروتينات
        }

        if (isCancelled) {
            throw CancellationException("تم إلغاء المسح")
        }
    }

    /**
     * اكتشاف أقسام التخزين المتاحة
     *
     * يقرأ ملف /proc/partitions أو يستخدم أوامر روت
     * للحصول على قائمة بأقسام التخزين
     *
     * @return قائمة بمسارات أجهزة الأقسام
     */
    private fun discoverPartitions(): List<String> {
        val partitions = mutableListOf<String>()

        try {
            val procPartitions = java.io.File("/proc/partitions")
            if (procPartitions.canRead()) {
                procPartitions.readLines().drop(2).forEach { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val deviceName = parts[3]
                        // تجاهل الأقسام غير الفعالة والتمديدات
                        if (!deviceName.startsWith("loop") &&
                            !deviceName.contains("ram") &&
                            !deviceName.contains("zram")) {
                            partitions.add("/dev/block/$deviceName")
                        }
                    }
                }
            }

            // إضافة مسارات إضافية معروفة
            val commonPartitions = listOf(
                "/dev/block/bootdevice/by-name/userdata",
                "/dev/block/bootdevice/by-name/cache",
                "/dev/block/platform/soc/by-name/userdata",
                "/dev/block/by-name/userdata"
            )

            for (path in commonPartitions) {
                val file = java.io.File(path)
                if (file.exists() && path !in partitions) {
                    partitions.add(path)
                }
            }

            // محاولة استخدام lsblk عبر su
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "lsblk -no PATH"))
                val reader = process.inputStream.bufferedReader()
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("/dev/") && trimmed !in partitions) {
                        val blockFile = java.io.File(trimmed)
                        if (blockFile.exists()) {
                            partitions.add(trimmed)
                        }
                    }
                }
                reader.close()
                process.waitFor()
            } catch (_: Exception) {
                // أوامر su غير متاحة - نستخدم ما وجدناه من /proc
            }

        } catch (e: Exception) {
            // فشل في قراءة أقسام - نعيد القائمة الفارغة أو الجزئية
        }

        return partitions.distinct()
    }
}
