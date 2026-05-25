package com.ultimaterecovery.pro.engine.recovery

import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import java.io.File

/**
 * فئات البيانات لنتائج الاستعادة
 *
 * تحتوي هذه الفئة على جميع هياكل البيانات المستخدمة لتمثيل
 * نتائج عملية استعادة الملفات المحذوفة، بما في ذلك:
 * - حالة سلامة الملف المستعاد
 * - مستوى ثقة الاستعادة
 * - نتيجة استعادة ملف واحد
 * - نتيجة استعادة مجموعة ملفات (دفعة)
 * - معلومات الملف المكتشف أثناء المسح
 */

/**
 * حالة سلامة الملف المستعاد
 *
 * تصف مدى سلامة الملف بعد عملية الاستعادة:
 * - INTACT: الملف سليم بالكامل ويمكن قراءته بدون مشاكل
 * - PARTIAL: تم استعادة جزء من الملف (قد يكون غير مكتمل لكنه قابل للاستخدام جزئياً)
 * - CORRUPTED: الملف تالف - البيانات موجودة لكن البنية معطوبة
 * - UNREADABLE: الملف غير قابل للقراءة تماماً
 */
enum class IntegrityStatus {

    /** الملف سليم بالكامل - جميع البايتات موجودة وصحيحة */
    INTACT,

    /** استعادة جزئية - جزء من البيانات مفقود لكن الباقي قابل للاستخدام */
    PARTIAL,

    /** الملف تالف - البيانات موجودة لكن البنية الداخلية معطوبة */
    CORRUPTED,

    /** غير قابل للقراءة - لا يمكن تحليل محتوى الملف على الإطلاق */
    UNREADABLE;

    /** هل الملف قابل للاستخدام بأي شكل؟ */
    val isRecoverable: Boolean
        get() = this == INTACT || this == PARTIAL

    /** هل يحتاج الملف إلى محاولة إصلاح؟ */
    val needsRepair: Boolean
        get() = this == CORRUPTED || this == PARTIAL
}

/**
 * مستوى ثقة الاستعادة
 *
 * يصف مدى الثقة في أن الملف المستعاد صحيح وكامل:
 * - HIGH: ثقة عالية - الملف كامل وتم التحقق من سلامته
 * - MEDIUM: ثقة متوسطة - الملف على الأرجح صحيح لكن لم يتم التحقق بالكامل
 * - LOW: ثقة منخفضة - الملف قد يكون تالفاً أو غير مكتمل
 * - UNCERTAIN: غير مؤكد - لا يمكن تحديد حالة الملف
 */
enum class RecoveryConfidence(val percentage: Int) {

    /** ثقة عالية > 80% - الملف كامل ومتحقق */
    HIGH(85),

    /** ثقة متوسطة 50-80% - المحتوى على الأرجح صحيح */
    MEDIUM(65),

    /** ثقة منخفضة 20-50% - قد يكون تالفاً */
    LOW(35),

    /** غير مؤكد < 20% - لا يمكن التحقق */
    UNCERTAIN(10);

    /** هل تستحق محاولة الاستعادة؟ */
    val isWorthRecovering: Boolean
        get() = this != UNCERTAIN

    companion object {
        /**
         * حساب مستوى الثقة بناءً على نسبة البيانات المستعادة
         *
         * @param recoveredBytes عدد البايتات المستعادة
         * @param expectedBytes عدد البايتات المتوقعة (الحجم الأصلي)
         * @return مستوى الثقة المحسوب
         */
        fun fromRecoveryRatio(recoveredBytes: Long, expectedBytes: Long): RecoveryConfidence {
            if (expectedBytes <= 0L) return UNCERTAIN
            val ratio = (recoveredBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)
            return when {
                ratio >= 0.95f -> HIGH
                ratio >= 0.70f -> MEDIUM
                ratio >= 0.30f -> LOW
                else           -> UNCERTAIN
            }
        }
    }
}

/**
 * نتيجة استعادة ملف واحد
 *
 * تحتوي على جميع المعلومات حول محاولة استعادة ملف محذوف:
 * - الملف الأصلي وموقعه
 * - نجاح أو فشل العملية
 * - مسار الإخراج إذا نجحت
 * - فحص السلامة
 * - مستوى الثقة
 * - رسالة الخطأ إن وجدت
 *
 * @property file الملف المصدر المراد استعادته
 * @property success هل نجحت عملية الاستعادة؟
 * @property outputPath مسار الملف المستعاد (null إذا فشلت)
 * @property integrityCheck نتيجة فحص سلامة الملف المستعاد
 * @property recoveryConfidence مستوى الثقة في صحة الاستعادة
 * @property error رسالة الخطأ (null إذا نجحت)
 * @property recoveredSize عدد البايتات المستعادة فعلياً
 * @property originalSize الحجم الأصلي المتوقع للملف
 * @property durationMs المدة المستغرقة في المللي ثانية
 * @property repairAttempted هل تمت محاولة إصلاح الملف؟
 */
data class RecoveryResult(
    val file: File,
    val success: Boolean,
    val outputPath: String? = null,
    val integrityCheck: IntegrityStatus = IntegrityStatus.UNREADABLE,
    val recoveryConfidence: RecoveryConfidence = RecoveryConfidence.UNCERTAIN,
    val error: String? = null,
    val recoveredSize: Long = 0L,
    val originalSize: Long = 0L,
    val durationMs: Long = 0L,
    val repairAttempted: Boolean = false
) {
    /** نسبة البيانات المستعادة من الحجم الأصلي */
    val recoveryRatio: Float
        get() = if (originalSize > 0L) {
            (recoveredSize.toFloat() / originalSize.toFloat()).coerceIn(0f, 1f)
        } else 0f

    /** هل الملف المستعاد قابل للاستخدام؟ */
    val isUsable: Boolean
        get() = success && integrityCheck.isRecoverable

    /** وصف نصي لحالة الاستعادة */
    val statusDescription: String
        get() = when {
            success && integrityCheck == IntegrityStatus.INTACT -> "تم الاستعادة بنجاح - ملف سليم"
            success && integrityCheck == IntegrityStatus.PARTIAL -> "تم الاستعادة جزئياً"
            success && integrityCheck == IntegrityStatus.CORRUPTED -> "تم الاستعادة لكن الملف تالف"
            !success && error != null -> "فشل الاستعادة: $error"
            else -> "فشل الاستعادة - سبب غير معروف"
        }
}

/**
 * نتيجة استعادة دفعة من الملفات
 *
 * تجميع لنتائج استعادة عدة ملفات مع إحصائيات شاملة:
 * - قائمة النتائج الفردية
 * - عدد الملفات الناجحة والفاشلة
 * - الحجم الإجمالي
 * - المدة الإجمالية
 *
 * @property results قائمة نتائج الاستعادة لكل ملف
 * @property totalFiles إجمالي عدد الملفات المطلوب استعادتها
 * @property successCount عدد الملفات المستعادة بنجاح
 * @property failedCount عدد الملفات التي فشلت استعادتها
 * @property totalSize الحجم الإجمالي للملفات المستعادة بنجاح (بالبايت)
 * @property duration المدة الإجمالية للاستعادة (بالمللي ثانية)
 */
data class RecoveryBatch(
    val results: List<RecoveryResult>,
    val totalFiles: Int,
    val successCount: Int,
    val failedCount: Int,
    val totalSize: Long,
    val duration: Long
) {
    /** نسبة النجاح الإجمالية */
    val successRate: Float
        get() = if (totalFiles > 0) successCount.toFloat() / totalFiles else 0f

    /** عدد الملفات ذات السلامة الكاملة */
    val intactCount: Int
        get() = results.count { it.integrityCheck == IntegrityStatus.INTACT }

    /** عدد الملفات التالفة */
    val corruptedCount: Int
        get() = results.count { it.integrityCheck == IntegrityStatus.CORRUPTED }

    /** متوى الثقة الإجمالي */
    val averageConfidence: RecoveryConfidence
        get() {
            val successful = results.filter { it.success }
            if (successful.isEmpty()) return RecoveryConfidence.UNCERTAIN
            val avgPercentage = successful.map { it.recoveryConfidence.percentage }.average()
            return when {
                avgPercentage >= RecoveryConfidence.HIGH.percentage -> RecoveryConfidence.HIGH
                avgPercentage >= RecoveryConfidence.MEDIUM.percentage -> RecoveryConfidence.MEDIUM
                avgPercentage >= RecoveryConfidence.LOW.percentage -> RecoveryConfidence.LOW
                else -> RecoveryConfidence.UNCERTAIN
            }
        }

    companion object {
        /** إنشاء دفعة فارغة */
        fun empty() = RecoveryBatch(
            results = emptyList(),
            totalFiles = 0,
            successCount = 0,
            failedCount = 0,
            totalSize = 0L,
            duration = 0L
        )

        /**
         * إنشاء دفعة من قائمة النتائج الفردية
         *
         * @param results قائمة نتائج الاستعادة
         * @param duration المدة الإجمالية
         * @return دفعة الاستعادة مع الإحصائيات المحسوبة تلقائياً
         */
        fun fromResults(results: List<RecoveryResult>, duration: Long): RecoveryBatch {
            val successful = results.filter { it.success }
            return RecoveryBatch(
                results = results,
                totalFiles = results.size,
                successCount = successful.size,
                failedCount = results.size - successful.size,
                totalSize = successful.sumOf { it.recoveredSize },
                duration = duration
            )
        }
    }
}

/**
 * معلومات الملف المكتشف أثناء المسح
 *
 * يمثل ملفاً محذوفاً تم اكتشافه أثناء عملية المسح.
 * يحتوي على جميع البيانات الوصفية المتاحة قبل عملية الاستعادة الفعلية.
 *
 * @property path المسار الأصلي للملف (أو المسار التقريبي إذا تم الاكتشاف عبر التوقيعات)
 * @property fileName اسم الملف
 * @property fileSize حجم الملف بالبايت (قد يكون تقديرياً في المسح العميق)
 * @property extension امتداد الملف
 * @property mimeType نوع MIME
 * @property category تصنيف الملف
 * @property signatureName اسم التوقيع المطابق (من FileSignatures)
 * @property offsetInBlock الإزاحة داخل الكتلة/القطاع حيث تم العثور على الملف (للمسح العميق)
 * @property confidence مستوى الثقة في صحة الاكتشاف
 * @property isFragment هل الملف مُجزأ (موزع على عدة مواقع غير متصلة)؟
 * @property thumbnailPath مسار الصورة المصغرة إن وُجدت
 * @property lastModified تاريخ آخر تعديل معروف
 * @property isRootRequired هل يحتاج الوصول إلى صلاحيات الروت لاستعادته؟
 * @property sourcePath مسار المصدر (قد يختلف عن path في حالة المسح الخام)
 * @property metadata بيانات وصفية إضافية (EXIF, ID3, etc.)
 */
data class FoundFileInfo(
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val extension: String,
    val mimeType: String,
    val category: FileCategory,
    val signatureName: String? = null,
    val offsetInBlock: Long = -1L,
    val confidence: RecoveryConfidence = RecoveryConfidence.MEDIUM,
    val isFragment: Boolean = false,
    val thumbnailPath: String? = null,
    val lastModified: Long = 0L,
    val isRootRequired: Boolean = false,
    val sourcePath: String = path,
    val metadata: Map<String, String> = emptyMap()
) {
    /** هل حجم الملف معروف بدقة؟ */
    val isSizeAccurate: Boolean
        get() = fileSize > 0L && !isFragment

    /** هل الملف قابل للاستعادة بدون صلاحيات روت؟ */
    val isRecoverableWithoutRoot: Boolean
        get() = !isRootRequired
}

/**
 * معلومات تقدم عملية الاستعادة
 *
 * @property totalFiles إجمالي الملفات المطلوب استعادتها
 * @property processedFiles عدد الملفات المعالجة حتى الآن
 * @property currentFile اسم الملف الحالي قيد الاستعادة
 * @property bytesRecovered إجمالي البايتات المستعادة
 * @property totalBytes إجمالي البايتات المطلوب استعادتها
 * @property estimatedTimeRemainingMs الوقت المتبقي المقدر بالمللي ثانية
 */
data class RecoveryProgress(
    val totalFiles: Int,
    val processedFiles: Int,
    val currentFile: String,
    val bytesRecovered: Long,
    val totalBytes: Long,
    val estimatedTimeRemainingMs: Long = 0L
) {
    /** نسبة التقدم من 0.0 إلى 1.0 */
    val progress: Float
        get() = if (totalFiles > 0) processedFiles.toFloat() / totalFiles else 0f

    /** نسبة تقدم البيانات من 0.0 إلى 1.0 */
    val dataProgress: Float
        get() = if (totalBytes > 0L) bytesRecovered.toFloat() / totalBytes else 0f
}
