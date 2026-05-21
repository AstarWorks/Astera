package com.astarworks.astera.core.application.config.backup

import com.astarworks.astera.core.domain.backup.BackupRetentionPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML wire-shape for `content/balance/backup-policy.yaml` (Wave 5w / ADR-0089).
 *
 * Domain types ([BackupRetentionPolicy]) stay annotation-free; this DTO carries
 * the `@Serializable` + `@SerialName` decoration so kaml can parse the file
 * without leaking serialization into `*.domain.*`.
 *
 * Example file:
 *
 * ```yaml
 * retention:
 *   hourly: 24
 *   daily: 7
 *   weekly: 4
 * schedule:
 *   postgres_incremental_interval_minutes: 60
 *   daily_full_backup_hour_utc: 4
 * restic:
 *   binary_path: restic
 *   repository_uri: s3:s3.eu-central-1.amazonaws.com/astera-backups
 * ```
 */
@Serializable
public data class BackupPolicyYaml(
    @SerialName("retention")
    public val retention: RetentionYaml,

    @SerialName("schedule")
    public val schedule: ScheduleYaml = ScheduleYaml(),

    @SerialName("restic")
    public val restic: ResticYaml = ResticYaml(),
) {
    public fun retentionToDomain(): BackupRetentionPolicy = BackupRetentionPolicy(
        hourly = retention.hourly,
        daily = retention.daily,
        weekly = retention.weekly,
    )

    @Serializable
    public data class RetentionYaml(
        public val hourly: Int = 24,
        public val daily: Int = 7,
        public val weekly: Int = 4,
    )

    @Serializable
    public data class ScheduleYaml(
        @SerialName("postgres_incremental_interval_minutes")
        public val postgresIncrementalIntervalMinutes: Int = 60,
        @SerialName("daily_full_backup_hour_utc")
        public val dailyFullBackupHourUtc: Int = 4,
    )

    @Serializable
    public data class ResticYaml(
        @SerialName("binary_path")
        public val binaryPath: String = "restic",
        @SerialName("repository_uri")
        public val repositoryUri: String = "",
    )
}
