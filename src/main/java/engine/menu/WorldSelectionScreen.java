package engine.menu;

import engine.Main;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.Button;
import engine.render.UIScreenScaler;
import engine.world.World;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

public class WorldSelectionScreen implements MenuScreen {
    private final Main main;
    private final MenuManager manager;
    private final Input input;
    private final UIScreenScaler scaler;
    private final List<WorldEntry> worldList = new ArrayList<>();
    private final List<Rectangle> entryBounds = new ArrayList<>();
    private int selectedIndex = -1;
    private float scrollY = 0f;

    public WorldSelectionScreen(Main main,
                                Input input,
                                UIScreenScaler scaler,
                                MenuManager manager) {
        this.main    = main;
        this.input   = input;
        this.scaler  = scaler;
        this.manager = manager;
        loadWorldList();
    }

    private void loadWorldList() {
        String cwd = System.getProperty("user.dir");
        File saveFolder = new File(cwd, "saves");
        if (!saveFolder.exists() || !saveFolder.isDirectory()) return;

        worldList.clear();
        for (File file : saveFolder.listFiles()) {
            if (file.isDirectory()) {
                worldList.add(new WorldEntry(
                        file.getName().replace('_',' '),
                        file.getName(),
                        file.lastModified()
                ));
            }
        }
        worldList.sort(Comparator.comparingLong(w -> -w.lastModified));
        if (!worldList.isEmpty()) selectedIndex = 0;
    }

    @Override
    public void render(TextRenderer text) {
        final float s            = 2f;
        final float LIST_X       = 100f;
        final float LIST_Y0      = 200f;
        final float ENTRY_WIDTH  = 700f;
        final float ENTRY_HEIGHT = 50f;
        final float DATE_OFFSET  = 700f;
        final int   fbW          = (int) scaler.getWidth();
        final int   fbH          = (int) scaler.getHeight();
        final float cx           = fbW / 2f;
        final float bottom       = fbH - 400f;  // marge basse
        final Button btn         = manager.getUIButton();

        // 1) récupère souris en pixels, top‐down une fois pour toutes
        float mx     = input.getMouseX();
        float myRaw  = input.getMouseY();
        float my     = myRaw;

        // 1) Titre
        text.drawCentered("Select World", cx, 100f, s);

        // 3) Clear & dessine la liste
        entryBounds.clear();
        float startY = LIST_Y0 - scrollY;
        for (int i = 0; i < worldList.size(); i++) {
            WorldEntry e = worldList.get(i);
            float x = LIST_X;
            float y = startY + i * ENTRY_HEIGHT;

            // si complètement hors‐cadre, on skip le dessin (mais on skip aussi la hit‐box)
            if (y + ENTRY_HEIGHT < 100f || y > bottom - ENTRY_HEIGHT) {
                continue;
            }

            // on mémorise la hit‐box exacte
            Rectangle box = new Rectangle(
                    (int)x, (int)y,
                    (int)ENTRY_WIDTH, (int)ENTRY_HEIGHT
            );
            entryBounds.add(box);

            // hover et sélection
            boolean hover = box.contains(mx, my);
            if (i == selectedIndex || hover) {
                glColor4f(1f, 1f, 1f, 0.2f);
                glBegin(GL_QUADS);
                glVertex2f(x,              y);
                glVertex2f(x + ENTRY_WIDTH, y);
                glVertex2f(x + ENTRY_WIDTH, y + ENTRY_HEIGHT);
                glVertex2f(x,              y + ENTRY_HEIGHT);
                glEnd();
            }

            // nom du monde à gauche
            glColor3f(1f, 1f, 1f);
            text.draw(e.name, x + 5f, y + 5f, s);

            // date à droite
            String date = new Date(e.lastModified).toString();
            text.draw(date, x + DATE_OFFSET, y + 5f, s);

            // gestion du clic **immédiat** dans la même boucle
            if (hover && input.isMouseClicked(0)) {
                selectedIndex = i;
                System.out.println("Selected index: " + selectedIndex);
            }
        }

        // 3) Boutons du bas
        float btnW = 600f, btnH = 100f;

        // Play Selected World
        btn.draw(
                cx - btnW/2f -400f,
                bottom - 100f,
                btnW, btnH,
                "Play Selected World",
                s,
                () -> {
                    if (selectedIndex >= 0 && selectedIndex < worldList.size()) {
                        WorldEntry e = worldList.get(selectedIndex);
                        // crée ou charge un monde à partir de son dossier
                        World w = new World(e.folderName.hashCode());
                        main.loadWorld(w);
                        manager.setScreen(new LoadingScreen(main, input, scaler));
                    }
                }
        );

        // Create New World
        btn.draw(
                cx - btnW/2f + 400f,
                bottom - 100f,
                btnW, btnH,
                "Create New World",
                s,
                () -> manager.setScreen(
                        new CreateWorldScreen(main, input, scaler, manager))
        );

        // Back
        btn.draw(
                100f,
                bottom - 100f,
                150f, 100f,
                "Back",
                s,
                () -> manager.setScreen(
                        new MainMenuScreen(main, input, scaler, manager))
        );
    }

    @Override
    public void handleClick(float x, float y) {

    }

    @Override
    public void handleKey(int key, int action) {
        if (action == GLFW_PRESS) {
            if (key == GLFW_KEY_UP) {
                scrollY = Math.max(0f, scrollY - 50f);
            } else if (key == GLFW_KEY_DOWN) {
                scrollY += 50f;
            }
        }
    }

    @Override public void handleKeyTyped(char c) { /* non utilisé */ }
    @Override public boolean blocksGame()      { return true; }

    private static class WorldEntry {
        final String name, folderName;
        final long   lastModified;
        WorldEntry(String name, String folderName, long lm) {
            this.name         = name;
            this.folderName   = folderName;
            this.lastModified = lm;
        }
    }
}