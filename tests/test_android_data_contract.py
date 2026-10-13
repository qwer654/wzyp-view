"""Regression guards for Android background data updates.

These are source-contract tests, not emulator/instrumentation tests. The Gradle
build additionally compiles all Android code and validates the signed APK.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android-app/app/src/main/java/cn/wzyp/view"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class AndroidDataSyncContracts(unittest.TestCase):
    def test_single_sqlite_owner_in_webview_and_jobs(self):
        store = read(JAVA / "OfflineStore.java")
        self.assertIn("private static OfflineStore sharedInstance;", store)
        self.assertIn("static synchronized OfflineStore getInstance(", store)
        # No second store handle should be created in UI or background JobServices.
        for name in ("MainActivity", "PublicRefreshJob", "DeltaSyncJob"):
            source = read(JAVA / (name + ".java"))
            self.assertIn("OfflineStore.getInstance(", source, name)
            self.assertNotIn("new OfflineStore(", source, name)

    def test_public_refresh_can_cancel_without_partial_shop_merge(self):
        updater = read(JAVA / "PublicStorefrontUpdater.java")
        self.assertIn("activeConnections", updater)
        self.assertIn("connection.disconnect();", updater)
        self.assertIn("public void cancel()", updater)
        self.assertRegex(updater, re.compile(
            r"requireActive\(\);\s*JSONObject summary=store\.mergePublicShop"))
        job = read(JAVA / "PublicRefreshJob.java")
        self.assertIn("updater.cancel();", job)

    def test_sync_and_backup_paths_remain_separate(self):
        manager = read(JAVA / "DataUpdateManager.java")
        screen = read(JAVA / "MainActivity.java")
        store = read(JAVA / "OfflineStore.java")
        self.assertIn("ACTION_CREATE_DOCUMENT", manager)
        self.assertIn("store.exportSnapshot(out)", manager)
        self.assertIn("onExportResult(result,data)", screen)
        self.assertIn("PRAGMA quick_check", store)
        self.assertIn("PRAGMA journal_mode", store)
        self.assertIn("public synchronized JSONObject exportSnapshot", store)
        self.assertIn("InterruptedException", read(JAVA / "DeltaSyncEngine.java"))

    def test_ui_can_cancel_refresh_and_export_backup(self):
        direct_ui = read(ROOT / "tools/enable_public_shop_ui.py")
        database_ui = read(ROOT / "tools/enable_db_update_ui.py")
        self.assertIn('id="publicShopCancel"', direct_ui)
        self.assertIn("window.WzypData.publicShopCancel()", direct_ui)
        self.assertIn("setPublicBusy(false)", direct_ui)
        self.assertIn("window.WzypData.exportFile()", database_ui)
        self.assertIn("export_error", database_ui)

    def test_stable_production_signing_and_permissions(self):
        gradle = read(ROOT / "android-app/app/build.gradle")
        manifest = read(ROOT / "android-app/app/src/main/AndroidManifest.xml")
        workflow = read(ROOT / ".github/workflows/android-apk.yml")
        version_code=re.search(r"versionCode\s+(\d+)",gradle)
        version_name=re.search(r"versionName\s+'([^']+)'",gradle)
        self.assertIsNotNone(version_code)
        self.assertIsNotNone(version_name)
        self.assertGreaterEqual(int(version_code.group(1)),11)
        self.assertRegex(version_name.group(1),r"^\d+\.\d+\.\d+$")
        self.assertIn("applicationId 'cn.wzyp.view'", gradle)
        self.assertIn("android.permission.ACCESS_NETWORK_STATE", manifest)
        self.assertIn("assembleRelease", workflow)
        self.assertIn("versionCode='"+version_code.group(1)+"'", workflow)
        self.assertIn("versionName='"+version_name.group(1)+"'", workflow)
        self.assertIn("apksigner", workflow)
        self.assertIn("d1547a5aaea202c1811b47590bf8d6cc8a5dfaa7de0b79a29878c470d4914be8", workflow)


if __name__ == "__main__":
    unittest.main()
