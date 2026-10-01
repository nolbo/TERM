package com.termux.terminal;

import java.util.List;

/** Wire examples and state transitions from the kitty graphics specification. */
public class KittyProtocolTest extends TerminalTestCase {
    private static String command(String header, String data) { return "\033_G" + header + ";" + data + "\033\\"; }
    private void send(String header) { enterString(command(header, "")); }
    private void red(String header) { enterString(command("f=24,s=1,v=1," + header, "/wAA")); }
    private TerminalGraphics.Placement placement(long image, long placement) {
        return mTerminal.getGraphics().findPlacement(image, placement, false);
    }
    private List<TerminalGraphics.Placement> rendered() {
        return mTerminal.getGraphics().getRenderPlacements(mTerminal.getScreen(), 0, mTerminal.mRows, mTerminal.mColumns, 0);
    }
    private static final String CELL = new String(Character.toChars(KittyPlaceholders.CODE_POINT));

    public void testImageNumbersAllocateNewIdsAndResolveNewest() {
        withTerminalSized(20, 8);
        red("a=T,I=42,C=1");
        long first = placement(1, 0).imageId;
        assertEquals(command("i=1,I=42", "OK"), mOutput.getOutputAndClear());
        red("a=t,I=42");
        assertEquals(command("i=2,I=42", "OK"), mOutput.getOutputAndClear());
        send("a=p,I=42,p=7,C=1");
        assertEquals(2L, placement(2, 7).imageId);
        send("a=d,d=N,I=42");
        assertNull(placement(2, 0));
        assertNotNull(placement(first, 0));
        send("a=p,I=42,p=8,C=1");
        assertNotNull(placement(first, 8));
    }

    public void testConflictingIdAndNumberRejected() {
        withTerminalSized(20, 8);
        red("a=T,i=1,I=2");
        assertTrue(mOutput.getOutputAndClear().contains("EINVAL"));
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
    }

    public void testCropOffsetsNegativeZAndCursorPolicy() {
        withTerminalSized(20, 8);
        enterString(command("a=T,i=1,p=2,f=24,s=2,v=1,x=1,w=1,X=2,Y=3,c=2,r=2,z=-3,C=1", "/wAAAP8A"));
        TerminalGraphics.Placement p = placement(1, 2);
        assertEquals(1, p.sourceX); assertEquals(1, p.sourceWidth);
        assertEquals(-3, p.z); assertEquals(2f / INITIAL_CELL_WIDTH_PIXELS, p.offsetXCells);
        assertEquals(2f, p.clipRight); assertEquals(2f, p.clipBottom);
        assertEquals(2, p.columns); assertEquals(2, p.rows);
        assertEquals(0, mTerminal.getCursorCol()); assertEquals(0, mTerminal.getCursorRow());
    }

    public void testOffsetConsumesExplicitDimensionAndImplicitDimensionKeepsAspect() {
        TerminalGraphics g = new TerminalGraphics();
        TerminalGraphics.Placement p = g.kitty("a=T,i=1,f=24,s=2,v=1,c=2,X=5,Y=3;/wAAAP8A", 0, 0, 10, 20, 0).placement;
        assertEquals(1.5f, p.widthCells); assertEquals(0.375f, p.heightCells);
        assertEquals(2f, p.clipRight); assertEquals(0.525f, p.clipBottom, 0.0001f);
        p = g.kitty("a=p,i=1,X=9,Y=19", 0, 0, 10, 20, 0).placement;
        assertEquals(0.2f, p.widthCells); assertEquals(0.05f, p.heightCells);
        assertEquals(2, p.columns); assertEquals(1, p.rows);
    }

    public void testEverySpatialDeleteSelectorAndRange() {
        withTerminalSized(20, 8);
        for (String selector : new String[]{"c", "p,x=3,y=2", "q,x=3,y=2,z=-7", "x,x=3", "y,y=2", "z,z=-7"}) {
            enterString("\033[2;3H"); red("a=T,i=1,p=2,c=2,r=2,z=-7,C=1");
            send("a=d,d=" + selector);
            assertNull(selector, placement(1, 2));
        }
        red("a=T,i=10,C=1"); red("a=T,i=11,C=1"); red("a=T,i=12,C=1");
        send("a=d,d=R,x=10,y=11");
        assertNull(placement(10, 0)); assertNull(placement(11, 0)); assertNotNull(placement(12, 0));
        send("a=p,i=10"); assertTrue(mOutput.getOutputAndClear().contains("ENOENT"));
    }

    public void testDeleteAllPreservesScrollbackAndVirtualPlacements() {
        withTerminalSized(20, 8);
        red("a=T,i=1,C=1"); enterString("\033[8;1H\n");
        red("a=T,i=2,C=1"); red("a=T,i=3,U=1,c=2,r=2");
        send("a=d,d=A");
        assertNotNull(placement(1, 0)); assertNull(placement(2, 0)); assertNotNull(placement(3, 0));
        send("a=d,d=I,i=3"); assertNull(placement(3, 0));
    }

    public void testDeleteAbortsPendingUploadInsteadOfBecomingAChunk() {
        withTerminalSized(20, 8);
        red("a=T,i=1,C=1");
        enterString(command("a=T,i=2,f=24,s=2,v=1,m=1", "/wAA"));
        send("a=d,d=A");
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
        red("a=T,i=3,C=1"); assertNotNull(placement(3, 0));
    }

    public void testOrdinaryErasureDoesNotDeleteGraphicsButClearScreenDoes() {
        withTerminalSized(20, 8);
        red("a=T,i=1,c=2,r=2,C=1");
        enterString("\033[2K\033[X\033[J");
        assertNotNull(placement(1, 0));
        enterString("\033[2J"); assertNull(placement(1, 0));
    }

    public void testScrollMarginsClipContainedPlacementsAndLeaveIntersectingOnes() {
        withTerminalSized(20, 8);
        enterString("\033[3;1H"); red("a=T,i=1,c=2,r=2,C=1");
        enterString("\033[2;1H"); red("a=T,i=2,c=2,r=3,C=1");
        enterString("\033[3;6r\033[6;1H\n");
        assertEquals(1, placement(1, 0).row);
        assertEquals(1f, placement(1, 0).clipTop);
        assertEquals(1, placement(2, 0).row);
        assertEquals(0f, placement(2, 0).clipTop);
    }

    public void testRelativePlacementsMoveWithParentAndCascadeOnDeletion() {
        withTerminalSized(20, 8);
        red("a=T,i=1,p=1,c=2,r=2,C=1");
        red("a=t,i=2"); send("a=p,i=2,p=2,P=1,Q=1,H=3,V=2");
        rendered(); assertEquals(3, placement(2, 2).column); assertEquals(2, placement(2, 2).row);
        assertEquals(0, mTerminal.getCursorCol()); assertEquals(0, mTerminal.getCursorRow());
        enterString("\033[4;5H"); send("a=p,i=1,p=1,C=1");
        rendered(); assertEquals(7, placement(2, 2).column); assertEquals(5, placement(2, 2).row);
        send("a=d,d=i,i=1,p=1"); assertNull(placement(2, 2));
        send("a=p,i=2"); assertTrue(mOutput.getOutputAndClear().contains("ENOENT"));
    }

    public void testRelativeCyclesMissingParentsAndVirtualChildrenRejected() {
        withTerminalSized(20, 8);
        red("a=T,i=1,p=1,C=1"); red("a=t,i=2");
        send("a=p,i=2,p=2,P=1,Q=1,C=1");
        mOutput.getOutputAndClear();
        send("a=p,i=1,p=1,P=2,Q=2"); assertTrue(mOutput.getOutputAndClear().contains("ECYCLE"));
        send("a=p,i=2,p=3,P=7,Q=1"); assertTrue(mOutput.getOutputAndClear().contains("ENOPARENT"));
        send("a=p,i=2,U=1,P=1,Q=1"); assertTrue(mOutput.getOutputAndClear().contains("EINVAL"));
    }

    public void testUnicodePlaceholdersAndInheritanceUseColorNumbersNotPaletteRgb() {
        withTerminalSized(20, 8);
        red("a=T,i=42,U=1,c=3,r=2,q=2");
        enterString("\033[38;5;42m" + CELL + "\u0305" + CELL + CELL + "\r\n" + CELL + "\u030d" + CELL + CELL);
        List<TerminalGraphics.Placement> tiles = rendered();
        assertEquals(6, tiles.size());
        assertEquals(0f, tiles.get(0).clipTop); assertEquals(2f, tiles.get(2).clipLeft);
        assertEquals(1f, tiles.get(3).clipTop); assertEquals(2f, tiles.get(5).clipLeft);
        assertEquals(0, tiles.get(5).column); assertEquals(0, tiles.get(5).row);
        enterString("\033[2J"); assertTrue(rendered().isEmpty());
        assertNotNull(placement(42, 0)); // Clearing text does not delete the virtual prototype.
    }

    public void testPlaceholderHighByteUnderlineIdAndReset() {
        withTerminalSized(20, 8);
        red("a=T,i=33554474,p=7,U=1,c=2,r=1,q=2");
        enterString("\033[38;5;42m\033[58;5;7m" + CELL + "\u0305\u0305\u030e" + CELL);
        assertEquals(2, rendered().size());
        assertEquals(33554474L, rendered().get(1).imageId);
        enterString("\033[0mX");
        TerminalRow line = mTerminal.getScreen().allocateFullLineIfNecessary(mTerminal.getScreen().externalToInternalRow(0));
        assertEquals(7, line.getUnderlineColor(0)); assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, line.getUnderlineColor(2));
    }

    public void testPlaceholderReflowAndCopyPreservePlacementColor() {
        withTerminalSized(6, 4);
        red("a=T,i=42,p=9,U=1,c=3,r=1,q=2");
        enterString("\033[38;5;42m\033[58;5;9m" + CELL + "\u0305" + CELL + CELL);
        mTerminal.resize(3, 4, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS);
        assertEquals(3, rendered().size());
        enterString("\033[1;1H\033[@");
        assertEquals(2, rendered().size());
    }

    public void testRelativeAnchorFromPlaceholderText() {
        withTerminalSized(20, 8);
        red("a=T,i=1,p=1,U=1,c=1,r=1,q=2"); red("a=t,i=2");
        send("a=p,i=2,p=2,P=1,Q=1,H=2,V=1,C=1");
        assertTrue(rendered().isEmpty());
        enterString("\033[3;4H\033[38;5;1m\033[58;5;1m" + CELL + "\u0305\u0305");
        assertEquals(2, rendered().size());
        assertEquals(5, placement(2, 2).column); assertEquals(3, placement(2, 2).row);
    }

    public void testAnimationFrameCompositionAndClientSelection() {
        TerminalGraphics g = new TerminalGraphics();
        g.kitty("a=T,i=1,f=32,s=1,v=1,C=1;/wAA/w==", 0, 0, 10, 20, 100);
        g.kitty("a=f,i=1,f=32,s=1,v=1,c=1;AP8AgA==", 0, 0, 10, 20, 100);
        g.kitty("a=a,i=1,c=2", 0, 0, 10, 20, 100);
        assertEquals(0xff7f8000, g.getPlacements().get(0).image.pixels[0]);
        g.kitty("a=c,i=1,r=1,c=2,C=1", 0, 0, 10, 20, 100);
        assertEquals(0xffff0000, g.getPlacements().get(0).image.pixels[0]);
        TerminalGraphics.Result error = g.kitty("a=c,i=1,r=2,c=2", 0, 0, 10, 20, 100);
        assertTrue(error.response.contains("EINVAL"));
    }

    public void testAnimationLoadingLoopControlGaplessFramesAndDeletion() {
        KittyAnimation a = new KittyAnimation(new TerminalImage(1, 1, new int[]{0xffff0000}));
        a.addOrEdit(new TerminalImage(1, 1, new int[]{0xff00ff00}), 0, 0, 0, 0, 0, true, 40, 100);
        a.control(1, 1, 20, 3, 2, 100);
        assertEquals(20L, a.tick(100)); assertEquals(0xffff0000, a.image().pixels[0]);
        assertEquals(40L, a.tick(120)); assertEquals(0xff00ff00, a.image().pixels[0]);
        assertEquals(-1L, a.tick(160)); assertEquals(1, a.state);
        a.control(1, 1, -1, 2, 1, 200);
        assertEquals(40L, a.tick(200)); assertEquals(1, a.current);
        assertEquals(-1L, a.tick(300));
        a.addOrEdit(new TerminalImage(1, 1, new int[]{0xff0000ff}), 0, 0, 0, 0, 0, true, 40, 300);
        a.tick(300); assertEquals(2, a.current);
        a.delete(1, 301); assertEquals(2, a.frames.size()); assertEquals(1, a.current);
    }

    public void testStoppingAnimationAfterDetachUsesElapsedFrameWithoutRenderer() {
        TerminalGraphics graphics = new TerminalGraphics();
        graphics.kitty("a=T,i=1,f=32,s=1,v=1,C=1;/wAA/w==", 0, 0, 10, 20, 100);
        graphics.kitty("a=f,i=1,f=32,s=1,v=1,z=10;AP8A/w==", 0, 0, 10, 20, 100);
        graphics.kitty("a=a,i=1,c=1,r=1,z=10,s=3", 0, 0, 10, 20, 100);
        graphics.kitty("a=a,i=1,s=1", 0, 0, 10, 20, 115);
        assertEquals(0xff00ff00, graphics.getPlacements().get(0).image.pixels[0]);
    }

    public void testChunkedAnimationFrameAndInvalidControlRecovery() {
        withTerminalSized(20, 8);
        enterString(command("a=T,i=1,f=24,s=2,v=1,C=1", "/wAA/wAA"));
        enterString(command("a=f,i=1,f=24,s=2,v=1,m=1", "AP8A") + command("a=f,m=0", "AAD/"));
        send("a=a,i=1,c=2"); assertEquals(0xff0000ff, placement(1, 0).image.pixels[1]);
        send("a=a,i=1,s=8"); assertTrue(mOutput.getOutputAndClear().contains("EINVAL"));
        send("a=d,d=F,i=1,r=2"); send("a=a,i=1,c=2");
        assertTrue(mOutput.getOutputAndClear().contains("ENOENT"));
    }

    public void testRelativeReplacementCannotExceedDepthThroughExistingChildren() {
        withTerminalSized(20, 8);
        for (int id = 1; id <= 35; id++) red("a=t,i=" + id + ",q=2");
        send("a=p,i=1,p=1,C=1,q=2");
        for (int id = 2; id <= 33; id++) send("a=p,i=" + id + ",p=1,P=" + (id - 1) + ",Q=1,q=2");
        send("a=p,i=34,p=1,C=1,q=2");
        send("a=p,i=1,p=1,P=34,Q=1");
        assertTrue(mOutput.getOutputAndClear().contains("ETOODEEP"));
        assertNull(placement(1, 1).parent);
    }

    public void testAlternateScreenWideImagesScrollAndClip() {
        withTerminalSized(20, 8);
        enterString("\033[?1049h");
        red("a=T,i=1,c=30,r=2,C=1");
        enterString("\033[8;1H\n");
        assertEquals(-1, placement(1, 0).row);
        assertEquals(1f, placement(1, 0).clipTop);
        enterString("\n"); assertNull(placement(1, 0));
    }

    public void testClearScreenDeletesVisibleRelativeImageWithOffscreenParent() {
        withTerminalSized(20, 8);
        red("a=T,i=1,p=1,C=1");
        enterString("\033[8;1H\n");
        red("a=t,i=2"); send("a=p,i=2,p=2,P=1,Q=1,V=3,C=1");
        enterString("\033[2J");
        assertNotNull(placement(1, 1)); assertNull(placement(2, 2));
    }

    public void testColonTrueColorPlaceholderPlacementIdAndFrameAcknowledgement() {
        withTerminalSized(20, 8);
        red("a=T,i=42,p=7,U=1,c=1,r=1,q=2");
        enterString("\033[38:2::0:0:42m\033[58:2::0:0:7m" + CELL + "\u0305\u0305");
        assertEquals(1, rendered().size());
        red("a=f,i=42");
        assertEquals(command("i=42,r=2", "OK"), mOutput.getOutputAndClear());
        send("a=a,i=42,c=2"); assertEquals("", mOutput.getOutputAndClear());
    }

    public void testFrameQuotaAndGaplessLoopAreBounded() {
        KittyAnimation a = new KittyAnimation(new TerminalImage(1, 1, new int[]{0}));
        for (int i = 1; i < KittyAnimation.MAX_FRAMES; i++)
            a.addOrEdit(new TerminalImage(1, 1, new int[]{i}), 0, 0, 0, 0, 0, true, -1, 0);
        try {
            a.addOrEdit(new TerminalImage(1, 1, new int[]{0}), 0, 0, 0, 0, 0, true, -1, 0);
            fail("frame quota");
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().startsWith("ENOSPC")); }
        a.control(1, 0, 0, 3, 1, 0);
        assertEquals(-1L, a.tick(Long.MAX_VALUE / 2));
    }
}
