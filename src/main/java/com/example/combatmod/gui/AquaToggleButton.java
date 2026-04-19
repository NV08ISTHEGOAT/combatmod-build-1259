package com.example.combatmod.gui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import java.util.function.Consumer;
public class AquaToggleButton extends ClickableWidget {
    private static final int COLOR_ON_BG    = 0xFF004D5A;
    private static final int COLOR_OFF_BG   = 0xFF0F2030;
    private static final int COLOR_HOVER_BG = 0xFF006070;
    private static final int COLOR_BORDER   = 0xFF00E5FF;
    private static final int COLOR_TXT_ON   = 0xFF00E5FF;
    private static final int COLOR_TXT_OFF  = 0xFF4A7080;
    private static final int COLOR_GLOW     = 0x3300E5FF;
    private boolean toggled;
    private final String labelOn, labelOff;
    private final Consumer<AquaToggleButton> onToggle;
    public AquaToggleButton(int x, int y, int w, int h, String labelOn, String labelOff, boolean init, Consumer<AquaToggleButton> onToggle) {
        super(x, y, w, h, Text.literal(init ? labelOn : labelOff));
        this.toggled = init; this.labelOn = labelOn; this.labelOff = labelOff; this.onToggle = onToggle;
    }
    public boolean isToggled() { return toggled; }
    @Override
    protected void renderWidget(DrawContext ctx, int mx, int my, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        ctx.fill(x, y, x+w, y+h, isHovered() ? COLOR_HOVER_BG : (toggled ? COLOR_ON_BG : COLOR_OFF_BG));
        if (toggled) ctx.fill(x+1, y+1, x+w-1, y+h-1, COLOR_GLOW);
        ctx.fill(x, y, x+w, y+1, COLOR_BORDER);
        ctx.fill(x, y+h-1, x+w, y+h, COLOR_BORDER);
        ctx.fill(x, y, x+1, y+h, COLOR_BORDER);
        ctx.fill(x+w-1, y, x+w, y+h, COLOR_BORDER);
        ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, getMessage(), x+w/2, y+(h-8)/2, toggled ? COLOR_TXT_ON : COLOR_TXT_OFF);
    }
    @Override public void onClick(double mx, double my) {
        toggled = !toggled;
        setMessage(toggled ? Text.literal(labelOn) : Text.literal(labelOff));
        onToggle.accept(this);
    }
    @Override protected void appendClickableNarrations(NarrationMessageBuilder b) { b.put(NarrationPart.TITLE, getMessage()); }
}
