package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

final class SettingsBrand {
    private final Context context;
    private final String versionName;
    private Paint title, subtitle, version;
    private float k;

    SettingsBrand(Context context) {
        this.context = context;
        String name = "Free My TV";
        try {
            name += " " + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        versionName = name;
    }

    void size(float scale) {
        k = scale;
        title = centered(Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF));
        subtitle = centered(Ui.text(Ui.regular, 26 * k, 0x99FFFFFF));
        version = centered(Ui.text(Ui.regular, 22 * k, 0x66FFFFFF));
    }

    private static Paint centered(Paint paint) {
        paint.setTextAlign(Paint.Align.CENTER);
        return paint;
    }

    void draw(Canvas c) {
        if (title == null) return;
        float cx = 500 * k;
        Symbols.draw(context, c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
        c.drawText("Free My TV", cx, 612 * k, title);
        c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
        c.drawText(versionName, cx, 704 * k, version);
        c.drawText(Ui.th ? "แปลไทยโดย FB : Kiw Kiattisak" : "Thai translation by FB : Kiw Kiattisak",
                cx, 748 * k, version);
    }
}
