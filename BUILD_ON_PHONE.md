# Building Kich Launcher from a phone (GitHub Actions)

1. Create a free GitHub account and a new empty repository (private is fine).
2. Upload this project's files to it (GitHub mobile site: Add file > Upload files; or use
   the Termux `git` client). Keep the `.github/workflows/build-apk.yml` path intact.
3. Open the repository > Actions > "Build Kich Launcher APK" > Run workflow.
4. When the run finishes (about 10-20 minutes), open it and download the
   `kich-launcher-debug-apk` artifact (a zip). Extract it and install the .apk.
5. Allow "Install unknown apps" for your browser or file manager when asked.
6. Settings > Apps > Default apps > Home app > Kich Launcher.

The debug build installs as `com.kich.launcher.debug`, labelled "Kich Launcher debug".
