import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders fuse.png from the same geometry as fuse.svg, for systems without rsvg-convert.
 * Run: java app/desktop/packaging/RenderIcon.java app/desktop/packaging/fuse.png 256
 */
public class RenderIcon {
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "fuse.png";
        int px = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        double s = px;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        Color ink = new Color(0x0B, 0x0D, 0x12);
        Color mark = new Color(0xF2, 0xF4, 0xF8);
        Color spark = new Color(0xFF, 0x8A, 0x3D);

        // Ink tile.
        double bg = s * 0.22 * 2;
        g.setColor(ink);
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, bg, bg));

        // The mark in a box of 64% of the tile, centred.
        double m = s * 0.64;
        double o = (s - m) / 2;
        double stroke = m * 0.1;
        g.setColor(mark);
        g.setStroke(new BasicStroke((float) stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        double arc = m * 0.28 * 2;
        g.draw(new RoundRectangle2D.Double(o + stroke / 2, o + stroke / 2, m - stroke, m - stroke, arc, arc));

        Path2D.Double fuse = new Path2D.Double();
        fuse.moveTo(o + m * 0.28, o + m * 0.7);
        fuse.curveTo(o + m * 0.42, o + m * 0.7, o + m * 0.44, o + m * 0.34, o + m * 0.64, o + m * 0.34);
        g.setStroke(new BasicStroke((float) stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(fuse);

        double cx = o + m * 0.7;
        double cy = o + m * 0.3;
        double glow = m * 0.22;
        g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) glow,
            new float[] {0f, 1f}, new Color[] {spark, new Color(0xFF, 0x8A, 0x3D, 0)}));
        g.fill(new Ellipse2D.Double(cx - glow, cy - glow, glow * 2, glow * 2));
        double core = m * 0.07;
        g.setColor(spark);
        g.fill(new Ellipse2D.Double(cx - core, cy - core, core * 2, core * 2));

        g.dispose();
        ImageIO.write(img, "png", new File(out));
    }
}
