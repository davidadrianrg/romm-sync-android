package es.davidrg.rommsync.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Version comparison used by the in-app updater to decide whether the
 * latest GitHub release is newer than the installed build.
 */
class AppUpdateCheckerVersionTest {

    @Test fun `tag with v prefix newer than plain current`() {
        assertThat(AppUpdateChecker.isNewer("v0.4.58", "0.1.57")).isTrue()
    }

    @Test fun `same version is not newer`() {
        assertThat(AppUpdateChecker.isNewer("v0.1.57", "0.1.57")).isFalse()
    }

    @Test fun `older release is not newer`() {
        assertThat(AppUpdateChecker.isNewer("v0.1.56", "0.1.57")).isFalse()
    }

    @Test fun `numeric comparison not lexicographic`() {
        // "0.10.2" vs "0.9.30": 10 > 9 despite "10" < "9" as strings
        assertThat(AppUpdateChecker.isNewer("v0.10.2", "0.9.30")).isTrue()
        assertThat(AppUpdateChecker.isNewer("v0.9.30", "0.10.2")).isFalse()
    }

    @Test fun `minor bump wins over patch`() {
        assertThat(AppUpdateChecker.isNewer("v0.5.0", "0.4.99")).isTrue()
    }

    @Test fun `missing segments are zero`() {
        assertThat(AppUpdateChecker.isNewer("v0.4", "0.4.0")).isFalse()
        assertThat(AppUpdateChecker.isNewer("v0.4.1", "0.4")).isTrue()
    }

    @Test fun `build metadata and prerelease suffix are ignored`() {
        assertThat(AppUpdateChecker.isNewer("v1.2.3+build.5", "1.2.3")).isFalse()
        assertThat(AppUpdateChecker.isNewer("v1.2.4-beta", "1.2.3")).isTrue()
    }

    @Test fun `garbage segments parse as zero`() {
        assertThat(AppUpdateChecker.isNewer("vx.y.z", "0.0.0")).isFalse()
    }
}
