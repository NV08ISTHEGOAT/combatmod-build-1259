package com.example.combatmod.gui;
import com.example.combatmod.ModConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
public class CombatMenuScreen extends Screen {
    private static final int PANEL_W=260,PANEL_H=210;
    private static final int C_PANEL_BG=0xDD071B24,C_TITLE_BAR=0xFF002535,C_BORDER=0xFF00E5FF;
    private static final int C_SEPARATOR=0xFF004D60,C_TITLE=0xFF00E5FF,C_LABEL=0xFF80DEEA;
    private static final int C_SLIDER_TRACK=0xFF1A3040,C_SLIDER_FILL=0xFF00BCD4,C_SLIDER_KNOB=0xFF00E5FF;
    private static final int SLIDER_MIN=3,SLIDER_MAX=20;
    private int panelX,panelY;
    private boolean draggingSlider=false;
    public CombatMenuScreen(){super(Text.literal("Combat Utilities"));}
    @Override protected void init(){
        panelX=(this.width-PANEL_W)/2; panelY=(this.height-PANEL_H)/2;
        this.addDrawableChild(new AquaToggleButton(panelX+20,panelY+52,220,30,
            "  Invis Reveal  :  ON  ","  Invis Reveal  :  OFF  ",
            ModConfig.invisRevealEnabled, btn->ModConfig.invisRevealEnabled=btn.isToggled()));
        this.addDrawableChild(new AquaToggleButton(panelX+20,panelY+92,220,30,
            "  Extended Reach  :  ON  ","  Extended Reach  :  OFF  ",
            ModConfig.reachEnabled, btn->ModConfig.reachEnabled=btn.isToggled()));
    }
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        ctx.fill(panelX,panelY,panelX+PANEL_W,panelY+PANEL_H,C_PANEL_BG);
        ctx.fill(panelX+1,panelY+1,panelX+PANEL_W-1,panelY+32,C_TITLE_BAR);
        ctx.fill(panelX,panelY,panelX+PANEL_W,panelY+1,C_BORDER);
        ctx.fill(panelX,panelY+PANEL_H-1,panelX+PANEL_W,panelY+PANEL_H,C_BORDER);
        ctx.fill(panelX,panelY,panelX+1,panelY+PANEL_H,C_BORDER);
        ctx.fill(panelX+PANEL_W-1,panelY,panelX+PANEL_W,panelY+PANEL_H,C_BORDER);
        int cs=4;
        ctx.fill(panelX,panelY,panelX+cs,panelY+cs,C_BORDER);
        ctx.fill(panelX+PANEL_W-cs,panelY,panelX+PANEL_W,panelY+cs,C_BORDER);
        ctx.fill(panelX,panelY+PANEL_H-cs,panelX+cs,panelY+PANEL_H,C_BORDER);
        ctx.fill(panelX+PANEL_W-cs,panelY+PANEL_H-cs,panelX+PANEL_W,panelY+PANEL_H,C_BORDER);
        ctx.drawCenteredTextWithShadow(this.textRenderer,Text.literal("* COMBAT UTILITIES *"),this.width/2,panelY+12,C_TITLE);
        ctx.fill(panelX+10,panelY+32,panelX+PANEL_W-10,panelY+33,C_BORDER);
        ctx.drawCenteredTextWithShadow(this.textRenderer,Text.literal("-- TOGGLES --"),this.width/2,panelY+38,C_LABEL);
        super.render(ctx,mx,my,delta);
        ctx.fill(panelX+10,panelY+133,panelX+PANEL_W-10,panelY+134,C_SEPARATOR);
        ctx.drawCenteredTextWithShadow(this.textRenderer,Text.literal("Reach Distance: "+(int)ModConfig.reachDistance+" blocks"),this.width/2,panelY+141,C_LABEL);
        drawSlider(ctx);
        ctx.drawCenteredTextWithShadow(this.textRenderer,Text.literal("Press ESC to close"),this.width/2,panelY+PANEL_H-14,0xFF2A5060);
    }
    private void drawSlider(DrawContext ctx){
        int sx=panelX+20,sy=panelY+160,sw=220,sh=6,kw=10,kh=16;
        float t=(ModConfig.reachDistance-SLIDER_MIN)/(float)(SLIDER_MAX-SLIDER_MIN);
        int fp=(int)(t*sw),kx=sx+fp-kw/2;
        ctx.fill(sx,sy,sx+sw,sy+sh,C_SLIDER_TRACK);
        ctx.fill(sx,sy,sx+fp,sy+sh,C_SLIDER_FILL);
        ctx.fill(kx,sy-(kh-sh)/2,kx+kw,sy+sh+(kh-sh)/2,C_SLIDER_KNOB);
    }
    @Override public boolean mouseClicked(double mx,double my,int btn){
        if(mx>=panelX+14&&mx<=panelX+246&&my>=panelY+150&&my<=panelY+178){draggingSlider=true;updateSlider(mx);return true;}
        return super.mouseClicked(mx,my,btn);
    }
    @Override public boolean mouseDragged(double mx,double my,int btn,double dx,double dy){
        if(draggingSlider){updateSlider(mx);return true;} return super.mouseDragged(mx,my,btn,dx,dy);
    }
    @Override public boolean mouseReleased(double mx,double my,int btn){draggingSlider=false;return super.mouseReleased(mx,my,btn);}
    private void updateSlider(double mx){
        float t=(float)((mx-(panelX+20))/220); t=Math.max(0f,Math.min(1f,t));
        ModConfig.reachDistance=Math.round(SLIDER_MIN+t*(SLIDER_MAX-SLIDER_MIN));
    }
    @Override public boolean shouldPause(){return false;}
    @Override public boolean shouldCloseOnEsc(){return true;}
}
