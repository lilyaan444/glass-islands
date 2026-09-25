import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import javax.swing.*;
import java.awt.*;
import java.awt.peer.ComponentPeer;
import java.awt.peer.WindowPeer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Standalone validation of the native bridge on the JetBrains Runtime, outside the IDE.
 * Usage: native/test/run-harness.sh [glass|frosted]   (material of the control pill)
 */
public final class GlassHarness {

    /** Downcalls into libliquidglass through the Foreign Function & Memory API, like the plugin. */
    static final class Bridge {
        final Arena arena = Arena.ofShared();
        final SymbolLookup lookup;
        final Linker linker = Linker.nativeLinker();

        Bridge(String path) {
            lookup = SymbolLookup.libraryLookup(java.nio.file.Path.of(path), arena);
        }

        MethodHandle fn(String name, FunctionDescriptor descriptor) {
            return linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
        }

        int intCall(String name) throws Throwable {
            return (int) fn(name, FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact();
        }

        void applyBackdrop(long window, double[] config) throws Throwable {
            fn("rlg_apply_backdrop", FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
                    .invokeExact(window, arena.allocateFrom(ValueLayout.JAVA_DOUBLE, config));
        }

        void setIslands(long window, double[] shapes, int count, double[] config) throws Throwable {
            fn("rlg_set_islands", FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                    .invokeExact(window, arena.allocateFrom(ValueLayout.JAVA_DOUBLE, shapes), count, arena.allocateFrom(ValueLayout.JAVA_DOUBLE, config));
        }

        String describe(long window) throws Throwable {
            MemorySegment text = (MemorySegment) fn("rlg_describe_window", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
                    .invokeExact(window);
            try {
                return text.reinterpret(Long.MAX_VALUE).getString(0);
            } finally {
                fn("rlg_free_string", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)).invokeExact(text);
            }
        }

        int status(long window) throws Throwable {
            return (int) fn("rlg_window_status", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)).invokeExact(window);
        }
    }

    public static void main(String[] args) throws Throwable {
        boolean glass = args.length == 0 || args[0].equals("glass");
        Bridge bridge = new Bridge(System.getProperty("rlg.library"));
        System.out.printf("bridge=%d macOS=%d glassAvailable=%d accessibility=%d%n",
                bridge.intCall("rlg_bridge_version"), bridge.intCall("rlg_os_major_version"),
                bridge.intCall("rlg_glass_available"), bridge.intCall("rlg_accessibility_options"));

        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Liquid Glass harness");
            frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            frame.setContentPane(new IslandsPanel());
            frame.setSize(Integer.getInteger("rlg.width", 900), Integer.getInteger("rlg.height", 600));
            frame.setLocation(Integer.getInteger("rlg.x", 200), Integer.getInteger("rlg.y", 150));
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setVisible(true);
            try {
                makeTranslucent(frame);
                long ptr = nsWindowPointer(frame);
                double[] window = config(0);
                window[3] = 0.02; window[4] = 0.03; window[5] = 0.05; window[6] = 0.5;
                bridge.applyBackdrop(ptr, window);
                int h = frame.getHeight();
                // x, y, width, height, tint r, g, b, a, corner radius, shadow, role (1 = control), identifier
                double[] shapes = {
                        20, 50, 260, h - 120, 0.13, 0.15, 0.2, 0.6, 10, 1, 0, 1,
                        300, 50, frame.getWidth() - 320, h - 120, 0.13, 0.15, 0.2, 0.85, 10, 1, 0, 2,
                        frame.getWidth() / 2.0 - 60, h - 50, 120, 32, 0.22, 0.44, 0.88, 0.62, 8, 0.6, 1, 3,
                };
                bridge.setIslands(ptr, shapes, 3, config(glass ? 1 : 0));
                new Timer(800, e -> {
                    try {
                        System.out.printf("status=%d%n%s%n", bridge.status(ptr), bridge.describe(ptr));
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                    ((Timer) e.getSource()).stop();
                }).start();
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** RLG_CONFIG_LENGTH values: backend, appearance (1 = dark), alpha, tint rgba, contrast, material, radius. */
    static double[] config(int backend) {
        return new double[] {backend, 1, 1.0, 0, 0, 0, 0, 0, 0, 0};
    }

    static void makeTranslucent(JFrame frame) throws ReflectiveOperationException {
        Field background = Component.class.getDeclaredField("background");
        background.setAccessible(true);
        background.set(frame, new Color(0, 0, 0, 0));
        frame.getRootPane().setOpaque(false);
        frame.getLayeredPane().setOpaque(false);
        ((JComponent) frame.getContentPane()).setOpaque(false);
        ((WindowPeer) peer(frame)).setOpaque(false);
        // With -Dswing.bufferPerWindow=true (IntelliJ) the window BufferStrategy flips with SrcOver, which
        // accumulates translucent pixels until opaque: route this root pane through classic double buffering.
        Method disable = JRootPane.class.getDeclaredMethod("disableTrueDoubleBuffering");
        disable.setAccessible(true);
        disable.invoke(frame.getRootPane());
    }

    static ComponentPeer peer(Component component) throws ReflectiveOperationException {
        Object accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null);
        Method getPeer = accessor.getClass().getMethod("getPeer", Component.class);
        getPeer.setAccessible(true);
        return (ComponentPeer) getPeer.invoke(accessor, component);
    }

    static long nsWindowPointer(Window window) throws ReflectiveOperationException {
        Object platformWindow = peer(window).getClass().getMethod("getPlatformWindow").invoke(peer(window));
        Field ptr = platformWindow.getClass().getSuperclass().getDeclaredField("ptr");
        ptr.setAccessible(true);
        return ptr.getLong(platformWindow);
    }

    static final class IslandsPanel extends JPanel {
        IslandsPanel() {
            super(new BorderLayout());
            setOpaque(false);
            JButton button = new JButton("Click me");
            button.addActionListener(e -> button.setText("Clicked " + System.nanoTime() % 1000));
            JPanel south = new JPanel();
            south.setOpaque(false);
            south.add(button);
            add(south, BorderLayout.SOUTH);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(30, 32, 40, Integer.getInteger("rlg.islandAlpha", 150)));
            g2.fillRoundRect(20, 50, 260, getHeight() - 120, 18, 18);
            g2.setColor(new Color(25, 26, 32, Integer.getInteger("rlg.editorAlpha", 235)));
            g2.fillRoundRect(300, 50, getWidth() - 320, getHeight() - 120, 18, 18);
            g2.setColor(new Color(220, 225, 235));
            g2.setFont(new Font("Menlo", Font.PLAIN, 15));
            g2.drawString("editor island (alpha 235)", 320, 90);
            g2.drawString("tool window (alpha 150)", 40, 90);
            g2.dispose();
        }
    }
}
