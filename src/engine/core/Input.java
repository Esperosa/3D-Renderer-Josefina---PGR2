package engine.core;

import java.awt.Component;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.util.HashSet;
import java.util.Set;

/**
 * Tady spravuju nízkoúrovňový stav klávesnice a myši pro viewport.
 */
public class Input {

    private static final int KEY_COUNT = 512;
    private static final int MOUSE_BUTTONS = 8;

    private final boolean[] keyDown = new boolean[KEY_COUNT];
    private final boolean[] keyPressed = new boolean[KEY_COUNT];
    private final boolean[] keyReleased = new boolean[KEY_COUNT];

    private final boolean[] prevKeyDown = new boolean[KEY_COUNT];

    private int mouseX;
    private int mouseY;
    private int prevMouseX;
    private int prevMouseY;
    private int mouseDX;
    private int mouseDY;
    private final boolean[] mouseButtons = new boolean[MOUSE_BUTTONS];
    private final boolean[] prevMouseButtons = new boolean[MOUSE_BUTTONS];
    private final boolean[] mousePressed = new boolean[MOUSE_BUTTONS];
    private final boolean[] mouseReleased = new boolean[MOUSE_BUTTONS];
    private int scrollDelta;
    private int frameScrollDelta;
    private final Set<Character> pendingChars = new HashSet<>();
    private final Set<Character> frameChars = new HashSet<>();

    /**
     * Tady připojím AWT listenery k cílové komponentě.
     */
    public void attach(Component component) {
        component.setFocusable(true);
        component.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                clearState();
            }
        });
        component.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int k = e.getKeyCode();
                boolean freshPress = true;
                if (k >= 0 && k < KEY_COUNT) {
                    freshPress = !keyDown[k];
                    keyDown[k] = true;
                }
                char ch = Character.toLowerCase(e.getKeyChar());
                if (freshPress && ch != KeyEvent.CHAR_UNDEFINED) {
                    pendingChars.add(ch);
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                int k = e.getKeyCode();
                if (k >= 0 && k < KEY_COUNT) {
                    keyDown[k] = false;
                }
            }
        });

        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int b = e.getButton();
                if (b >= 0 && b < MOUSE_BUTTONS) {
                    mouseButtons[b] = true;
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int b = e.getButton();
                if (b >= 0 && b < MOUSE_BUTTONS) {
                    mouseButtons[b] = false;
                }
            }
        });

        component.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mouseX = e.getX();
                mouseY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                mouseX = e.getX();
                mouseY = e.getY();
            }
        });

        component.addMouseWheelListener((MouseWheelEvent e) -> scrollDelta += e.getWheelRotation());
    }

    /**
     * Tady jednou za snímek zpracovávám stav vstupu.
     */
    public void poll() {
        for (int i = 0; i < KEY_COUNT; i++) {
            keyPressed[i] = keyDown[i] && !prevKeyDown[i];
            keyReleased[i] = !keyDown[i] && prevKeyDown[i];
            prevKeyDown[i] = keyDown[i];
        }
        mouseDX = mouseX - prevMouseX;
        mouseDY = mouseY - prevMouseY;
        prevMouseX = mouseX;
        prevMouseY = mouseY;
        for (int i = 0; i < MOUSE_BUTTONS; i++) {
            mousePressed[i] = mouseButtons[i] && !prevMouseButtons[i];
            mouseReleased[i] = !mouseButtons[i] && prevMouseButtons[i];
            prevMouseButtons[i] = mouseButtons[i];
        }
        frameScrollDelta = scrollDelta;
        scrollDelta = 0;
        frameChars.clear();
        frameChars.addAll(pendingChars);
        pendingChars.clear();
    }

    /** @return vrátím true, když je klávesa právě držená */
    public boolean isKeyDown(int keyCode) {
        return keyCode >= 0 && keyCode < KEY_COUNT && keyDown[keyCode];
    }

    /** @return vrátím true jen ve framu, kdy klávesu poprvé stisknu */
    public boolean isKeyPressed(int keyCode) {
        return keyCode >= 0 && keyCode < KEY_COUNT && keyPressed[keyCode];
    }

    /** @return vrátím true jen ve framu, kdy klávesu uvolním */
    public boolean isKeyReleased(int keyCode) {
        return keyCode >= 0 && keyCode < KEY_COUNT && keyReleased[keyCode];
    }

    /** @return vrátím true, když je tlačítko myši právě držené */
    public boolean isMouseButtonDown(int button) {
        return button >= 0 && button < MOUSE_BUTTONS && mouseButtons[button];
    }

    /** @return vrátím true jen ve framu, kdy tlačítko myši stisknu */
    public boolean isMouseButtonPressed(int button) {
        return button >= 0 && button < MOUSE_BUTTONS && mousePressed[button];
    }

    /** @return vrátím true jen ve framu, kdy tlačítko myši uvolním */
    public boolean isMouseButtonReleased(int button) {
        return button >= 0 && button < MOUSE_BUTTONS && mouseReleased[button];
    }

    public boolean isShiftDown() {
        return isKeyDown(KeyEvent.VK_SHIFT);
    }

    public boolean isCtrlDown() {
        return isKeyDown(KeyEvent.VK_CONTROL);
    }

    public boolean isAltDown() {
        return isKeyDown(KeyEvent.VK_ALT);
    }

    public int getMouseX() {
        return mouseX;
    }

    public int getMouseY() {
        return mouseY;
    }

    /** @return vrátím horizontální pohyb myši od minulého framu */
    public int getMouseDX() {
        return mouseDX;
    }

    /** @return vrátím vertikální pohyb myši od minulého framu */
    public int getMouseDY() {
        return mouseDY;
    }

    /** @return vrátím změnu kolečka myši v aktuálním framu */
    public int getScrollDelta() {
        return frameScrollDelta;
    }

    public boolean isCharPressed(char c) {
        return frameChars.contains(Character.toLowerCase(c));
    }

    public void forceMouseDelta(int dx, int dy) {
        this.mouseDX = dx;
        this.mouseDY = dy;
    }

    private void clearState() {
        for (int i = 0; i < KEY_COUNT; i++) {
            keyDown[i] = false;
            keyPressed[i] = false;
            keyReleased[i] = false;
            prevKeyDown[i] = false;
        }
        for (int i = 0; i < MOUSE_BUTTONS; i++) {
            mouseButtons[i] = false;
            mousePressed[i] = false;
            mouseReleased[i] = false;
            prevMouseButtons[i] = false;
        }
        scrollDelta = 0;
        frameScrollDelta = 0;
        pendingChars.clear();
        frameChars.clear();
        mouseDX = 0;
        mouseDY = 0;
    }
}
