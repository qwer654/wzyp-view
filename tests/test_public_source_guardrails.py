"""Regression guards for public-source HTML failures and app-safe scheduling.

The Android Java compiler and generated UI Node syntax checker are also run by CI.
These tests catch accidental deletion of safety features between releases.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = ROOT / "android-app/app/src/main/java/cn/wzyp/view"

class TestPublicSourceGuardrails(unittest.TestCase):
    @staticmethod
    def source(filename):
        return (JAVA / filename).read_text(encoding="utf-8")

    def test_html_or_challenge_is_not_written_to_sqlite(self):
        src = self.source("PublicStorefrontUpdater.java")
        self.assertIn('contains("text/html")', src)
        self.assertIn("blockedResponse(host,status", src)
        self.assertIn("SOURCE_COOLDOWN_MS=12L*60L*60L*1000L", src)
        self.assertLess(src.index('contains("text/html")'),
                        src.index("store.mergePublicShop(token,snapshot)"))
        self.assertIn("requireActive();", src)

    def test_background_cooldown_remains_optional(self):
        src = self.source("PublicRefreshJob.java")
        self.assertIn("updater.backgroundCoolingDown()", src)
        self.assertIn("updater.refreshBatch(4,null)", src)

    def test_manual_skip_never_deletes_shop_data(self):
        src=self.source("PublicStorefrontUpdater.java")
        section=src.split("public JSONObject skipCurrentShop()",1)[1].split("private static void waitPaced",1)[0]
        self.assertIn('putInt("offset",position+1)',section)
        self.assertNotIn("DELETE FROM",section)
        self.assertNotIn("store.mergePublicShop(",section)

    def test_bridge_exposes_source_and_job_status(self):
        src=self.source("DataUpdateManager.java")
        self.assertIn("public String publicShopSkip()",src)
        self.assertIn("public String syncHealth()",src)
        self.assertIn("ACCESS_NETWORK_STATE",src)
        self.assertIn("getPendingJob(PublicRefreshJob.JOB_ID)",src)

    def test_signed_version_and_permission(self):
        gradle=(ROOT/"android-app/app/build.gradle").read_text(encoding="utf-8")
        manifest=(ROOT/"android-app/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn("versionCode 11",gradle)
        self.assertIn("versionName '8.0.3'",gradle)
        self.assertIn('android.permission.ACCESS_NETWORK_STATE',manifest)


if __name__=="__main__":
    unittest.main()
