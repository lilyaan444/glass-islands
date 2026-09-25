#import <AppKit/AppKit.h>
#import <QuartzCore/QuartzCore.h>
#import <objc/runtime.h>
#include <stdlib.h>
#include <string.h>

#import "LiquidGlassBridge.h"

static NSUserInterfaceItemIdentifier const RLGBackdropIdentifier = @"com.lilyanmuller.glassislands.backdrop";
static NSUserInterfaceItemIdentifier const RLGIslandsIdentifier = @"com.lilyanmuller.glassislands.islands";
static NSUserInterfaceItemIdentifier const RLGOverlayIdentifier = @"com.lilyanmuller.glassislands.overlay";
static NSUserInterfaceItemIdentifier const RLGShadowsIdentifier = @"com.lilyanmuller.glassislands.shadows";
static NSUserInterfaceItemIdentifier const RLGSurfacesIdentifier = @"com.lilyanmuller.glassislands.surfaces";
static NSUserInterfaceItemIdentifier const RLGControlsIdentifier = @"com.lilyanmuller.glassislands.controls";
static NSString *const RLGHighlightLayerName = @"com.lilyanmuller.glassislands.highlight";

static char RLGAppearanceCodeKey;
static char RLGOriginalWindowAppearanceKey;
static char RLGSignatureKey;
static char RLGShapeIdKey;
static char RLGShapeRadiusKey;
static char RLGShapeShadowKey;

#pragma mark - Configuration

typedef struct {
    int32_t backend;
    int32_t appearance;
    double alpha;
    double tint[4];
    BOOL increasedContrast;
    int32_t material;
    double cornerRadius;
} RLGConfig;

static RLGConfig RLGConfigFrom(const double *values) {
    RLGConfig config = {
        .backend = (int32_t)values[0],
        .appearance = (int32_t)values[1],
        .alpha = values[2],
        .tint = {values[3], values[4], values[5], values[6]},
        .increasedContrast = values[7] != 0,
        .material = (int32_t)values[8],
        .cornerRadius = values[9],
    };
    return config;
}

static NSVisualEffectMaterial RLGMaterial(int32_t material) {
    switch (material) {
        case RLG_MATERIAL_MENU: return NSVisualEffectMaterialMenu;
        case RLG_MATERIAL_POPOVER: return NSVisualEffectMaterialPopover;
        default: return NSVisualEffectMaterialUnderWindowBackground;
    }
}

#pragma mark - Passive views (never take clicks, keyboard focus or first responder)

@interface RLGVisualEffectBackdrop : NSVisualEffectView
@end

@implementation RLGVisualEffectBackdrop
- (NSView *)hitTest:(NSPoint)point { return nil; }
- (BOOL)acceptsFirstResponder { return NO; }
@end

API_AVAILABLE(macos(26.0))
@interface RLGGlassBackdrop : NSGlassEffectView
@end

@implementation RLGGlassBackdrop
- (NSView *)hitTest:(NSPoint)point { return nil; }
- (BOOL)acceptsFirstResponder { return NO; }
@end

/* Top-left origin, matching Swing coordinates of the root pane. */
@interface RLGPassthroughView : NSView
@end

@implementation RLGPassthroughView
- (BOOL)isFlipped { return YES; }
- (NSView *)hitTest:(NSPoint)point { return nil; }
- (BOOL)acceptsFirstResponder { return NO; }
@end

/* Bottom-left origin like its CALayer, so shadow paths need no flipping of the layer geometry. */
@interface RLGShadowView : NSView
@end

@implementation RLGShadowView
- (NSView *)hitTest:(NSPoint)point { return nil; }
- (BOOL)acceptsFirstResponder { return NO; }
@end

#pragma mark - Main-thread execution

/*
 * Runs `work` on the AppKit main thread and waits for it, exactly like the JDK's own native AWT calls
 * (ThreadUtilities performOnMainThreadWaiting): the wait also runs in AWTRunLoopMode, which the AppKit thread
 * services while it is itself blocked on the AWT event thread, so this cannot deadlock. Waiting keeps the native
 * shapes in step with the Swing content painted in the same EDT turn (no lag while resizing a splitter).
 */
@interface RLGMainThreadCall : NSObject
@property (nonatomic, copy) void (^work)(void);
@end

@implementation RLGMainThreadCall
- (void)run {
    @autoreleasepool {
        /* An AppKit exception must never cross into the JVM: it would abort the whole IDE. */
        @try {
            self.work();
        } @catch (NSException *exception) {
            NSLog(@"Glass Islands: native update failed: %@", exception);
        }
    }
}
@end

static void RLGOnMainThread(void (^work)(void)) {
    RLGMainThreadCall *call = [RLGMainThreadCall new];
    call.work = work;
    if (NSThread.isMainThread) {
        [call run];
        return;
    }
    [call performSelectorOnMainThread:@selector(run)
                           withObject:nil
                        waitUntilDone:YES
                                modes:@[NSRunLoopCommonModes, @"AWTRunLoopMode"]];
}

#pragma mark - View helpers

/* Resolves the pointer only if it still designates a live window of this process. */
static NSWindow *RLGLiveWindow(int64_t windowPtr) {
    for (NSWindow *window in NSApp.windows) {
        if ((int64_t)(intptr_t)(__bridge void *)window == windowPtr) {
            return window;
        }
    }
    return nil;
}

static NSView *RLGFindView(NSView *parent, NSUserInterfaceItemIdentifier identifier) {
    for (NSView *subview in parent.subviews) {
        if ([subview.identifier isEqualToString:identifier]) {
            return subview;
        }
    }
    return nil;
}

static BOOL RLGIsGlassView(NSView *view) {
    if (@available(macOS 26.0, *)) {
        return [view isKindOfClass:[NSGlassEffectView class]];
    }
    return NO;
}

static BOOL RLGIsDark(NSView *view) {
    NSAppearanceName match = [view.effectiveAppearance bestMatchFromAppearancesWithNames:@[NSAppearanceNameAqua, NSAppearanceNameDarkAqua]];
    return [match isEqualToString:NSAppearanceNameDarkAqua];
}

/* Stores `signature` on `view` and returns YES when it differs from the previous one, so unchanged state is never reapplied. */
static BOOL RLGSignatureChanged(id object, NSData *signature) {
    NSData *previous = objc_getAssociatedObject(object, &RLGSignatureKey);
    if ([previous isEqualToData:signature]) return NO;
    objc_setAssociatedObject(object, &RLGSignatureKey, signature, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    return YES;
}

static NSData *RLGSignature(const double *values, size_t count) {
    return [NSData dataWithBytes:values length:sizeof(double) * count];
}

static NSColor *RLGColor(double red, double green, double blue, double alpha) {
    if (alpha <= 0) return nil;
    return [NSColor colorWithSRGBRed:red green:green blue:blue alpha:alpha];
}

/* Islands root: shadows, stable frosted surfaces, then Liquid Glass controls on top. */
static NSView *RLGSurfacesHost(NSView *islandsRoot) {
    return RLGFindView(islandsRoot, RLGSurfacesIdentifier);
}

static NSView *RLGControlsHost(NSView *islandsRoot) {
    for (NSView *subview in islandsRoot.subviews) {
        if (@available(macOS 26.0, *)) {
            if ([subview isKindOfClass:[NSGlassEffectContainerView class]]) {
                return ((NSGlassEffectContainerView *)subview).contentView;
            }
        }
        if ([subview.identifier isEqualToString:RLGControlsIdentifier]) {
            return subview;
        }
    }
    return nil;
}

static NSArray<NSView *> *RLGShapeViews(NSView *islandsRoot) {
    if (islandsRoot == nil) return @[];
    NSArray<NSView *> *surfaces = RLGSurfacesHost(islandsRoot).subviews ?: @[];
    NSArray<NSView *> *controls = RLGControlsHost(islandsRoot).subviews ?: @[];
    return [surfaces arrayByAddingObjectsFromArray:controls];
}

#pragma mark - Description (debug report)

static NSString *RLGDescribeEffect(NSView *view) {
    if ([view isKindOfClass:[NSVisualEffectView class]]) {
        NSVisualEffectView *effectView = (NSVisualEffectView *)view;
        NSView *overlay = RLGFindView(view, RLGOverlayIdentifier);
        NSColor *tint = overlay.layer.backgroundColor == NULL ? nil : [NSColor colorWithCGColor:overlay.layer.backgroundColor];
        return [NSString stringWithFormat:@"material=%ld state=%ld tint=%@", (long)effectView.material, (long)effectView.state, tint];
    }
    if (RLGIsGlassView(view)) {
        if (@available(macOS 26.0, *)) {
            NSGlassEffectView *glassView = (NSGlassEffectView *)view;
            return [NSString stringWithFormat:@"glassStyle=%ld cornerRadius=%.1f tint=%@",
                (long)glassView.style, glassView.cornerRadius, glassView.tintColor];
        }
    }
    CGColorRef color = view.layer.backgroundColor;
    return [NSString stringWithFormat:@"tint=%@", color == NULL ? @"none" : [NSColor colorWithCGColor:color]];
}

static NSString *RLGDescribe(NSWindow *window) {
    NSView *contentView = window.contentView;
    NSView *frameView = contentView.superview;
    CALayer *contentLayer = contentView.layer;
    NSView *backdrop = RLGFindView(frameView, RLGBackdropIdentifier);
    NSView *islands = RLGFindView(frameView, RLGIslandsIdentifier);
    NSMutableString *text = [NSMutableString string];
    [text appendFormat:@"NSWindow: %@ opaque=%d backgroundAlpha=%.2f\n",
        NSStringFromClass(window.class), window.isOpaque, window.backgroundColor.alphaComponent];
    [text appendFormat:@"frame view: %@, subviews (back to front): ", NSStringFromClass(frameView.class)];
    for (NSView *subview in frameView.subviews) {
        [text appendFormat:@"%@ ", NSStringFromClass(subview.class)];
    }
    CGColorRef layerColor = contentLayer.backgroundColor;
    [text appendFormat:@"\ncontent view: %@ layer=%@ layerOpaque=%d layerBackground=%@ cornerRadius=%.1f masksToBounds=%d\n",
        NSStringFromClass(contentView.class), NSStringFromClass(contentLayer.class), contentLayer.isOpaque,
        layerColor == NULL ? @"none" : [NSColor colorWithCGColor:layerColor], contentLayer.cornerRadius, contentLayer.masksToBounds];
    CGColorRef frameColor = frameView.layer.backgroundColor;
    [text appendFormat:@"frame layer: %@ background=%@ cornerRadius=%.1f window background=%@\n",
        frameView.layer == nil ? @"none" : NSStringFromClass(frameView.layer.class),
        frameColor == NULL ? @"none" : [NSColor colorWithCGColor:frameColor], frameView.layer.cornerRadius, window.backgroundColor];
    [text appendFormat:@"window appearance: %@\n", window.effectiveAppearance.name];
    if (backdrop == nil) {
        [text appendString:@"backdrop: none\n"];
    } else {
        [text appendFormat:@"backdrop: %@ alpha=%.2f appearance=%@ %@\n",
            NSStringFromClass(backdrop.class), backdrop.alphaValue, backdrop.effectiveAppearance.name, RLGDescribeEffect(backdrop)];
    }
    NSArray<NSView *> *shapes = RLGShapeViews(islands);
    [text appendFormat:@"shapes: %lu", (unsigned long)shapes.count];
    for (NSView *shape in shapes) {
        [text appendFormat:@"\n  %@ %@ %@", NSStringFromClass(shape.class), NSStringFromRect(shape.frame), RLGDescribeEffect(shape)];
    }
    return text;
}

static int32_t RLGStatus(NSWindow *window) {
    NSView *frameView = window.contentView.superview;
    NSView *islands = RLGFindView(frameView, RLGIslandsIdentifier);
    NSView *controls = islands == nil ? nil : RLGControlsHost(islands);
    int32_t status = RLG_STATUS_WINDOW_FOUND;
    if (RLGFindView(frameView, RLGBackdropIdentifier) != nil) status |= RLG_STATUS_BACKDROP_ATTACHED;
    if (controls != nil && ![controls.identifier isEqualToString:RLGControlsIdentifier]) status |= RLG_STATUS_GLASS_BACKEND;
    if (!window.isOpaque) status |= RLG_STATUS_WINDOW_NON_OPAQUE;
    if (!window.contentView.layer.isOpaque) status |= RLG_STATUS_LAYER_NON_OPAQUE;
    if (RLGShapeViews(islands).count > 0) status |= RLG_STATUS_ISLANDS_ATTACHED;
    return status;
}

#pragma mark - Appearance

/* Setting an appearance re-renders the whole effect subtree, so it is only done when it actually changes. */
static void RLGApplyAppearance(NSView *view, int32_t appearance) {
    if ([objc_getAssociatedObject(view, &RLGAppearanceCodeKey) isEqual:@(appearance)]) return;
    objc_setAssociatedObject(view, &RLGAppearanceCodeKey, @(appearance), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    view.appearance = [NSAppearance appearanceNamed:appearance == RLG_APPEARANCE_LIGHT ? NSAppearanceNameAqua : NSAppearanceNameDarkAqua];
}

/*
 * Behind-window materials follow the appearance of their window, not only the one of the view. Popups (borderless
 * windows) keep the appearance they were created with, e.g. light during a dark theme preview, so it is aligned
 * with the glass while the glass is on and restored afterwards. Titled windows are left to the IDE.
 */
static void RLGSyncBorderlessWindowAppearance(NSWindow *window, int32_t appearance) {
    if ((window.styleMask & NSWindowStyleMaskTitled) != 0) return;
    NSAppearanceName wanted = appearance == RLG_APPEARANCE_LIGHT ? NSAppearanceNameAqua : NSAppearanceNameDarkAqua;
    if ([window.appearance.name isEqualToString:wanted]) return;
    if (objc_getAssociatedObject(window, &RLGOriginalWindowAppearanceKey) == nil) {
        objc_setAssociatedObject(window, &RLGOriginalWindowAppearanceKey, window.appearance ?: (id)NSNull.null, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    window.appearance = [NSAppearance appearanceNamed:wanted];
}

static void RLGRestoreWindowAppearance(NSWindow *window) {
    id original = objc_getAssociatedObject(window, &RLGOriginalWindowAppearanceKey);
    if (original == nil) return;
    window.appearance = original == (id)NSNull.null ? nil : original;
    objc_setAssociatedObject(window, &RLGOriginalWindowAppearanceKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

#pragma mark - Effects

/* Stretchable rounded mask, the documented way to round an NSVisualEffectView. */
static NSImage *RLGRoundedMask(CGFloat radius) {
    NSImage *mask = [NSImage imageWithSize:NSMakeSize(radius * 2 + 1, radius * 2 + 1) flipped:NO drawingHandler:^BOOL(NSRect rect) {
        [NSColor.blackColor setFill];
        [[NSBezierPath bezierPathWithRoundedRect:rect xRadius:radius yRadius:radius] fill];
        return YES;
    }];
    mask.capInsets = NSEdgeInsetsMake(radius, radius, radius, radius);
    mask.resizingMode = NSImageResizingModeStretch;
    return mask;
}

/*
 * Tint of a frosted view, drawn by a layer-backed overlay (the NSVisualEffectView layer belongs to AppKit).
 * Surfaces also get a specular rim: a hairline border and a soft highlight along the top edge, the cues that make
 * a pane read as a slab of glass rather than a flat colour.
 */
static void RLGApplyOverlay(NSView *view, NSColor *tint, CGFloat radius, BOOL rim, BOOL dark, BOOL contrast) {
    NSView *overlay = RLGFindView(view, RLGOverlayIdentifier);
    if (tint == nil && !rim) {
        [overlay removeFromSuperview];
        return;
    }
    if (overlay == nil) {
        overlay = [[RLGPassthroughView alloc] initWithFrame:view.bounds];
        overlay.identifier = RLGOverlayIdentifier;
        overlay.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        overlay.wantsLayer = YES;
        [view addSubview:overlay];
    }
    CALayer *layer = overlay.layer;
    layer.backgroundColor = tint.CGColor;
    layer.cornerRadius = radius;
    layer.cornerCurve = kCACornerCurveContinuous;
    layer.masksToBounds = YES;
    layer.borderWidth = rim ? 1 : 0;
    CGFloat rimAlpha = dark ? (contrast ? 0.28 : 0.10) : (contrast ? 0.24 : 0.07);
    layer.borderColor = rim ? [NSColor colorWithWhite:dark ? 1 : 0 alpha:rimAlpha].CGColor : NULL;

    CAGradientLayer *highlight = nil;
    for (CALayer *sublayer in layer.sublayers) {
        if ([sublayer.name isEqualToString:RLGHighlightLayerName]) highlight = (CAGradientLayer *)sublayer;
    }
    if (!rim) {
        [highlight removeFromSuperlayer];
        return;
    }
    if (highlight == nil) {
        highlight = [CAGradientLayer layer];
        highlight.name = RLGHighlightLayerName;
        highlight.autoresizingMask = kCALayerWidthSizable | kCALayerHeightSizable;
        [layer addSublayer:highlight];
    }
    CGFloat top = layer.contentsAreFlipped ? 0 : 1;
    highlight.frame = layer.bounds;
    highlight.colors = @[(id)[NSColor colorWithWhite:1 alpha:dark ? 0.07 : 0.35].CGColor, (id)[NSColor colorWithWhite:1 alpha:0].CGColor];
    highlight.startPoint = CGPointMake(0.5, top);
    highlight.endPoint = CGPointMake(0.5, top == 0 ? 0.35 : 0.65);
}

/*
 * Frosted surface: NSVisualEffectView is the material AppKit designs for window backgrounds and, with an active
 * state, looks the same whether or not Rider is the key window (NSGlassEffectView does not, see header).
 */
static void RLGConfigureSurface(NSVisualEffectView *view, NSColor *tint, CGFloat radius, BOOL rim, BOOL dark, BOOL contrast, int32_t material) {
    double tintValues[4] = {0, 0, 0, 0};
    [[tint colorUsingColorSpace:NSColorSpace.sRGBColorSpace] getRed:&tintValues[0] green:&tintValues[1] blue:&tintValues[2] alpha:&tintValues[3]];
    double values[] = {radius, rim, dark, contrast, material, tintValues[0], tintValues[1], tintValues[2], tintValues[3]};
    if (!RLGSignatureChanged(view, RLGSignature(values, sizeof(values) / sizeof(double)))) return;
    view.material = RLGMaterial(material);
    view.blendingMode = NSVisualEffectBlendingModeBehindWindow;
    view.state = NSVisualEffectStateActive;
    view.maskImage = radius > 0 ? RLGRoundedMask(radius) : nil;
    RLGApplyOverlay(view, tint, radius, rim, dark, contrast);
}

/* Liquid Glass control: tinted natively, which also dims it like any native control when Rider resigns key. */
static void RLGConfigureGlass(NSView *view, NSColor *tint, CGFloat radius) {
    double tintValues[4] = {0, 0, 0, 0};
    [[tint colorUsingColorSpace:NSColorSpace.sRGBColorSpace] getRed:&tintValues[0] green:&tintValues[1] blue:&tintValues[2] alpha:&tintValues[3]];
    double values[] = {radius, tintValues[0], tintValues[1], tintValues[2], tintValues[3]};
    if (!RLGSignatureChanged(view, RLGSignature(values, sizeof(values) / sizeof(double)))) return;
    if (@available(macOS 26.0, *)) {
        NSGlassEffectView *glassView = (NSGlassEffectView *)view;
        glassView.style = NSGlassEffectViewStyleRegular;
        glassView.cornerRadius = radius;
        glassView.tintColor = tint;
    }
}

#pragma mark - Backdrop (whole window)

static void RLGApplyBackdrop(NSWindow *window, const RLGConfig *config) {
    NSView *contentView = window.contentView;
    NSView *frameView = contentView.superview;
    NSView *backdrop = RLGFindView(frameView, RLGBackdropIdentifier);
    /*
     * The blur material does not always re-render when only its appearance changes (a popup whose window keeps a
     * light appearance during a dark theme preview stays light): a new appearance gets a new view. Theme switches
     * only, so this costs nothing in normal use.
     */
    NSNumber *previousAppearance = objc_getAssociatedObject(backdrop, &RLGAppearanceCodeKey);
    if (backdrop != nil && previousAppearance != nil && ![previousAppearance isEqual:@(config->appearance)]) {
        [backdrop removeFromSuperview];
        backdrop = nil;
    }
    if (backdrop == nil) {
        backdrop = [[RLGVisualEffectBackdrop alloc] initWithFrame:frameView.bounds];
        backdrop.identifier = RLGBackdropIdentifier;
        backdrop.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        NSView *above = RLGFindView(frameView, RLGIslandsIdentifier) ?: contentView;
        [frameView addSubview:backdrop positioned:NSWindowBelow relativeTo:above];
    }
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    RLGSyncBorderlessWindowAppearance(window, config->appearance);
    if (!NSEqualRects(backdrop.frame, frameView.bounds)) backdrop.frame = frameView.bounds;
    if (backdrop.alphaValue != config->alpha) backdrop.alphaValue = config->alpha;
    RLGApplyAppearance(backdrop, config->appearance);
    NSColor *tint = RLGColor(config->tint[0], config->tint[1], config->tint[2], config->tint[3]);
    BOOL rounded = config->cornerRadius > 0;
    RLGConfigureSurface((NSVisualEffectView *)backdrop, tint, config->cornerRadius, rounded, RLGIsDark(backdrop),
                        config->increasedContrast, config->material);
    [CATransaction commit];
}

#pragma mark - Islands (frosted surfaces and Liquid Glass controls)

static NSView *RLGCreateIslandsRoot(NSView *frameView) {
    RLGPassthroughView *root = [[RLGPassthroughView alloc] initWithFrame:frameView.bounds];
    root.identifier = RLGIslandsIdentifier;
    root.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;

    RLGShadowView *shadows = [[RLGShadowView alloc] initWithFrame:root.bounds];
    shadows.identifier = RLGShadowsIdentifier;
    shadows.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    shadows.wantsLayer = YES;
    [root addSubview:shadows];

    RLGPassthroughView *surfaces = [[RLGPassthroughView alloc] initWithFrame:root.bounds];
    surfaces.identifier = RLGSurfacesIdentifier;
    surfaces.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    [root addSubview:surfaces];
    return root;
}

/* Controls live in an NSGlassEffectContainerView when Liquid Glass is available, as AppKit recommends. */
static NSView *RLGEnsureControlsHost(NSView *root, BOOL glass) {
    NSView *host = RLGControlsHost(root);
    BOOL hostIsGlass = host != nil && ![host.identifier isEqualToString:RLGControlsIdentifier];
    if (host != nil && hostIsGlass == glass) return host;
    if (host != nil) {
        [(hostIsGlass ? host.superview : host) removeFromSuperview];
    }
    host = [[RLGPassthroughView alloc] initWithFrame:root.bounds];
    host.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    if (glass) {
        if (@available(macOS 26.0, *)) {
            NSGlassEffectContainerView *container = [[NSGlassEffectContainerView alloc] initWithFrame:root.bounds];
            container.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
            container.spacing = 0;
            container.contentView = host;
            [root addSubview:container];
            return host;
        }
    }
    host.identifier = RLGControlsIdentifier;
    [root addSubview:host];
    return host;
}

/*
 * Soft drop shadow under the shapes that ask for one (scaled down for small controls), as floating panes have on
 * macOS 26. An even-odd mask keeps the shadow out of every shape, so it only deepens what is around them and
 * never darkens the panes. Rebuilt only when the geometry changes.
 */
static void RLGUpdateShadows(NSView *root, NSArray<NSView *> *shapes, BOOL dark) {
    NSView *shadowView = RLGFindView(root, RLGShadowsIdentifier);
    CALayer *layer = shadowView.layer;
    if (layer == nil) return;
    NSMutableData *signature = [NSMutableData data];
    double header[] = {dark, shadowView.bounds.size.width, shadowView.bounds.size.height};
    [signature appendBytes:header length:sizeof(header)];
    for (NSView *view in shapes) {
        NSRect frame = view.frame;
        double values[] = {frame.origin.x, frame.origin.y, frame.size.width, frame.size.height,
                           [objc_getAssociatedObject(view, &RLGShapeRadiusKey) doubleValue],
                           [objc_getAssociatedObject(view, &RLGShapeShadowKey) doubleValue]};
        [signature appendBytes:values length:sizeof(values)];
    }
    if (!RLGSignatureChanged(shadowView, signature)) return;

    CGFloat height = shadowView.bounds.size.height;
    for (CALayer *old in [layer.sublayers copy]) {
        [old removeFromSuperlayer];
    }
    CGMutablePathRef maskPath = CGPathCreateMutable();
    CGPathAddRect(maskPath, NULL, layer.bounds);
    for (NSView *view in shapes) {
        NSRect flipped = view.frame;
        CGFloat radius = [objc_getAssociatedObject(view, &RLGShapeRadiusKey) doubleValue];
        CGFloat strength = [objc_getAssociatedObject(view, &RLGShapeShadowKey) doubleValue];
        CGRect rect = CGRectMake(flipped.origin.x, height - NSMaxY(flipped), flipped.size.width, flipped.size.height);
        CGPathRef shape = CGPathCreateWithRoundedRect(rect, MIN(radius, rect.size.width / 2), MIN(radius, rect.size.height / 2), NULL);
        CGPathAddPath(maskPath, NULL, shape);
        if (strength > 0) {
            CGFloat blur = MIN(dark ? 14 : 12, MIN(rect.size.width, rect.size.height) * 0.3);
            CALayer *shadow = [CALayer layer];
            shadow.frame = layer.bounds;
            shadow.shadowPath = shape;
            shadow.shadowColor = NSColor.blackColor.CGColor;
            shadow.shadowOpacity = (float)((dark ? 0.55 : 0.18) * strength);
            shadow.shadowRadius = blur;
            shadow.shadowOffset = CGSizeMake(0, -blur * 0.3);
            [layer addSublayer:shadow];
        }
        CGPathRelease(shape);
    }
    CAShapeLayer *mask = [layer.mask isKindOfClass:[CAShapeLayer class]] ? (CAShapeLayer *)layer.mask : [CAShapeLayer layer];
    mask.frame = layer.bounds;
    mask.fillRule = kCAFillRuleEvenOdd;
    mask.path = maskPath;
    layer.mask = mask;
    CGPathRelease(maskPath);
}

static void RLGIndexShapeViews(NSView *host, NSMutableDictionary<NSNumber *, NSView *> *index) {
    for (NSView *view in host.subviews) {
        NSNumber *identifier = objc_getAssociatedObject(view, &RLGShapeIdKey);
        if (identifier != nil) index[identifier] = view;
    }
}

/*
 * Views are matched to shapes by the stable identifier the caller gives each shape, so a view never jumps from
 * one surface to another (which glass would animate as a morph), and unchanged shapes are not touched at all.
 */
static void RLGApplyIslands(NSWindow *window, const double *shapes, int32_t count, const RLGConfig *config) {
    NSView *contentView = window.contentView;
    NSView *frameView = contentView.superview;
    NSView *root = RLGFindView(frameView, RLGIslandsIdentifier);
    if (count == 0) {
        [root removeFromSuperview];
        return;
    }
    if (root == nil) {
        root = RLGCreateIslandsRoot(frameView);
        [frameView addSubview:root positioned:NSWindowBelow relativeTo:contentView];
    }

    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    if (!NSEqualRects(root.frame, frameView.bounds)) root.frame = frameView.bounds;
    if (root.alphaValue != config->alpha) root.alphaValue = config->alpha;
    RLGApplyAppearance(root, config->appearance);
    BOOL dark = RLGIsDark(root);
    BOOL glass = config->backend == RLG_BACKEND_GLASS && rlg_glass_available();
    NSView *surfaces = RLGSurfacesHost(root);
    NSView *controls = RLGEnsureControlsHost(root, glass);

    NSMutableDictionary<NSNumber *, NSView *> *unused = [NSMutableDictionary dictionary];
    RLGIndexShapeViews(surfaces, unused);
    RLGIndexShapeViews(controls, unused);
    for (int32_t i = 0; i < count; i++) {
        const double *shape = shapes + i * RLG_ISLAND_STRIDE;
        BOOL control = shape[10] != 0;
        NSNumber *identifier = @((int64_t)shape[11]);
        NSView *host = control ? controls : surfaces;
        NSView *view = unused[identifier];
        BOOL wantsGlassView = control && glass;
        if (view != nil && (view.superview != host || RLGIsGlassView(view) != wantsGlassView)) {
            [view removeFromSuperview];
            view = nil;
        }
        [unused removeObjectForKey:identifier];
        if (view == nil) {
            if (wantsGlassView) {
                if (@available(macOS 26.0, *)) view = [[RLGGlassBackdrop alloc] initWithFrame:NSZeroRect];
            }
            if (view == nil) view = [[RLGVisualEffectBackdrop alloc] initWithFrame:NSZeroRect];
            objc_setAssociatedObject(view, &RLGShapeIdKey, identifier, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            [host addSubview:view];
        }
        NSRect frame = NSMakeRect(shape[0], shape[1], shape[2], shape[3]);
        if (!NSEqualRects(view.frame, frame)) view.frame = frame;
        objc_setAssociatedObject(view, &RLGShapeRadiusKey, @(shape[8]), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        objc_setAssociatedObject(view, &RLGShapeShadowKey, @(shape[9]), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        NSColor *tint = RLGColor(shape[4], shape[5], shape[6], shape[7]);
        if (RLGIsGlassView(view)) {
            RLGConfigureGlass(view, tint, shape[8]);
        } else {
            RLGConfigureSurface((NSVisualEffectView *)view, tint, shape[8], !control, dark, config->increasedContrast, RLG_MATERIAL_WINDOW);
        }
    }
    for (NSView *stale in unused.allValues) {
        [stale removeFromSuperview];
    }
    RLGUpdateShadows(root, RLGShapeViews(root), dark);
    [CATransaction commit];
}

#pragma mark - C ABI

int32_t rlg_bridge_version(void) {
    return RLG_BRIDGE_VERSION;
}

int32_t rlg_os_major_version(void) {
    return (int32_t)NSProcessInfo.processInfo.operatingSystemVersion.majorVersion;
}

int32_t rlg_glass_available(void) {
    if (@available(macOS 26.0, *)) {
        return NSClassFromString(@"NSGlassEffectView") != nil;
    }
    return 0;
}

int32_t rlg_accessibility_options(void) {
    __block int32_t options = 0;
    RLGOnMainThread(^{
        NSWorkspace *workspace = NSWorkspace.sharedWorkspace;
        if (workspace.accessibilityDisplayShouldReduceTransparency) options |= RLG_ACCESSIBILITY_REDUCE_TRANSPARENCY;
        if (workspace.accessibilityDisplayShouldIncreaseContrast) options |= RLG_ACCESSIBILITY_INCREASE_CONTRAST;
        if (workspace.accessibilityDisplayShouldReduceMotion) options |= RLG_ACCESSIBILITY_REDUCE_MOTION;
    });
    return options;
}

/* Runs `work` against the live window on the main thread; a window that is gone is silently skipped. */
static void RLGOnWindow(int64_t windowPtr, void (^work)(NSWindow *window)) {
    RLGOnMainThread(^{
        NSWindow *window = RLGLiveWindow(windowPtr);
        if (window != nil && window.contentView.superview != nil) work(window);
    });
}

void rlg_apply_backdrop(int64_t nsWindow, const double *config) {
    if (nsWindow == 0 || config == NULL) return;
    RLGConfig parsed = RLGConfigFrom(config);
    RLGOnWindow(nsWindow, ^(NSWindow *window) {
        RLGApplyBackdrop(window, &parsed);
    });
}

void rlg_set_islands(int64_t nsWindow, const double *shapes, int32_t count, const double *config) {
    if (nsWindow == 0 || config == NULL || count < 0 || (count > 0 && shapes == NULL)) return;
    RLGConfig parsed = RLGConfigFrom(config);
    RLGOnWindow(nsWindow, ^(NSWindow *window) {
        RLGApplyIslands(window, shapes, count, &parsed);
    });
}

void rlg_remove_backdrop(int64_t nsWindow) {
    if (nsWindow == 0) return;
    RLGOnWindow(nsWindow, ^(NSWindow *window) {
        [RLGFindView(window.contentView.superview, RLGBackdropIdentifier) removeFromSuperview];
        RLGRestoreWindowAppearance(window);
    });
}

int32_t rlg_window_status(int64_t nsWindow) {
    __block int32_t status = 0;
    RLGOnMainThread(^{
        NSWindow *window = RLGLiveWindow(nsWindow);
        if (window != nil && window.contentView.superview != nil) status = RLGStatus(window);
    });
    return status;
}

char *rlg_describe_window(int64_t nsWindow) {
    __block NSString *description = @"NSWindow not found among NSApp.windows";
    RLGOnMainThread(^{
        NSWindow *window = RLGLiveWindow(nsWindow);
        if (window != nil && window.contentView.superview != nil) description = RLGDescribe(window);
    });
    return strdup(description.UTF8String);
}

void rlg_free_string(char *value) {
    free(value);
}
