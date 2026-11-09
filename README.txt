Copy the folders (app/, core/, feature/) over your project, same paths.

NEW file : core/ui/.../PermissionDeclared.kt
DELETE   : app/src/main/res/drawable/widget_motivation_bg.xml
           app/src/main/res/drawable/widget_motivation_btn.xml

Permission UI now follows AndroidManifest.xml: a permission with no <uses-permission> line
gets no prompt, toggle or settings row. Not compiled or run on a device - build and test.
