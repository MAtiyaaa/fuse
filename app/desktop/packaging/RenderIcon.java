import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders Fuse's icon from the same geometry as fuse.svg, for systems without rsvg-convert and for
 * the Windows and macOS packages. The output's extension picks the format:
 *
 *   java app/desktop/packaging/RenderIcon.java app/desktop/packaging/fuse.png 256
 *   java app/desktop/packaging/RenderIcon.java app/desktop/packaging/fuse.ico
 *   java app/desktop/packaging/RenderIcon.java app/desktop/packaging/fuse.icns
 *
 * .ico holds PNGs from 16 to 256 px (Windows Vista and later read them). .icns holds PNGs from 16 to
 * 1024 px, with the tile inset to macOS's icon grid (824 of 1024 px) so it sits with other apps.
 */
public class RenderIcon {
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "fuse.png";
        if (out.endsWith(".ico")) {
            writeIco(new File(out), new int[] {16, 24, 32, 48, 64, 128, 256});
        } else if (out.endsWith(".icns")) {
            writeIcns(new File(out));
        } else {
            int px = args.length > 1 ? Integer.parseInt(args[1]) : 256;
            ImageIO.write(render(px, 0.0), "png", new File(out));
        }
    }

    /** The icon at [px] square, the tile inset by [inset] of the size on every side. */
    static BufferedImage render(int px, double inset) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double pad = px * inset;
        g.translate(pad, pad);
        double s = px - pad * 2;

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
        return img;
    }

    static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }

    /** ICONDIR, one ICONDIRENTRY per size, then the PNGs (little-endian, sizes of 256 written as 0). */
    static void writeIco(File out, int[] sizes) throws Exception {
        List<byte[]> images = new ArrayList<>();
        for (int px : sizes) images.add(png(render(px, 0.0)));
        int offset = 6 + 16 * sizes.length;
        ByteBuffer head = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            int px = sizes[i];
            head.put((byte) (px >= 256 ? 0 : px)).put((byte) (px >= 256 ? 0 : px));
            head.put((byte) 0).put((byte) 0);
            head.putShort((short) 1).putShort((short) 32);
            head.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        try (FileOutputStream f = new FileOutputStream(out)) {
            f.write(head.array());
            for (byte[] image : images) f.write(image);
        }
    }

    /** "icns", the total length, then one PNG element per OSType (big-endian). */
    static void writeIcns(File out) throws Exception {
        String[] types = {"icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10"};
        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        for (int i = 0; i < types.length; i++) {
            // macOS's grid: an 824 px tile centred in 1024.
            byte[] image = png(render(sizes[i], 100.0 / 1024.0));
            data.writeBytes(types[i]);
            data.writeInt(image.length + 8);
            data.write(image);
        }
        try (DataOutputStream f = new DataOutputStream(new FileOutputStream(out))) {
            f.writeBytes("icns");
            f.writeInt(body.size() + 8);
            f.write(body.toByteArray());
        }
    }
}
