package com.fluxin.apktoolmd.support;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.components.BubbleView;
import com.fluxin.apktoolmd.support.components.ICategory;
import com.fluxin.apktoolmd.support.components.ICheckBox;
import com.fluxin.apktoolmd.support.components.ICollapse;
import com.fluxin.apktoolmd.support.components.IInputFloat;
import com.fluxin.apktoolmd.support.components.IInputInt;
import com.fluxin.apktoolmd.support.components.IInputText;
import com.fluxin.apktoolmd.support.components.IRadioButton;
import com.fluxin.apktoolmd.support.components.ISlider;
import com.fluxin.apktoolmd.support.components.ISpinner;
import com.fluxin.apktoolmd.support.components.ISwitch;
import com.fluxin.apktoolmd.support.components.ITextView;
import com.fluxin.apktoolmd.support.components.IButton;
import com.fluxin.apktoolmd.support.components.IButtonLink;
import com.fluxin.apktoolmd.support.components.IconView;
import com.fluxin.apktoolmd.support.components.MarkView;
import com.fluxin.apktoolmd.support.components.StatusView;
import com.fluxin.apktoolmd.support.components.Theme;
import com.fluxin.apktoolmd.support.components.Ui;
import com.fluxin.apktoolmd.support.entity.FeatureEntity;

public class Menu extends BaseMenu implements Ui.Host {

    public static int POS_X = 0;
    public static int POS_Y = 100;

    private static final String GENERAL_TAB = "General";
    private static final String SETTINGS_TAB = "Settings";
    private static final int SAVE_SETTINGS_ID = -1;
    private static final String SAVE_SETTINGS = "Save settings";

    WindowManager mWindowManager;
    WindowManager.LayoutParams vmParams;
    private WindowManager.LayoutParams canvasParams;

    DrawView drawView;
    private final Ui ui;
    private FrameLayout root;
    private BubbleView bubble;
    private LinearLayout panel;
    private ScrollView sidebarScroll, contentScroll;
    private LinearLayout sidebar, content;
    private TextView titleText, creditText;

    private ISwitch iSwitch;
    private ICheckBox iCheckBox;
    private ISlider iSlider;
    private ISpinner iSpinner;
    private IRadioButton iRadioButton;
    private IButton iButton;
    private IButtonLink iButtonLink;
    private IInputText iInputText;
    private IInputInt iInputInt;
    private IInputFloat iInputFloat;
    private ITextView iTextView;
    private ICategory iCategory;
    private ICollapse iCollapse;

    private final java.util.List<Tab> tabs = new java.util.ArrayList<>();
    private final java.util.Deque<Page> pages = new java.util.ArrayDeque<>();
    private final java.util.Map<Object, LinearLayout> pageCache = new java.util.IdentityHashMap<>();
    private int activeTab = -1;
    private boolean expanded;
    private int bubbleX, bubbleY;
    private EditText editing;

    private static class Tab {
        String name, icon;
        java.util.List<FeatureEntity> items;
        boolean settings;
        IconView iconView;
        TextView label;
    }

    private static class Page {
        final String title;
        final java.util.List<FeatureEntity> items;

        Page(String title, java.util.List<FeatureEntity> items) {
            this.title = title;
            this.items = items;
        }
    }

    public Menu(Context context) {
        super(context);
        drawView = new DrawView(getContext);
        ui = new Ui(context, typeface, this);
        InitComponent(context);
    }

    private void InitComponent(Context context) {
        iSwitch = new ISwitch(ui);
        iCheckBox = new ICheckBox(ui);
        iSlider = new ISlider(ui);
        iSpinner = new ISpinner(ui);
        iRadioButton = new IRadioButton(ui);
        iButton = new IButton(ui);
        iButtonLink = new IButtonLink(ui);
        iInputText = new IInputText(ui);
        iInputInt = new IInputInt(iInputText);
        iInputFloat = new IInputFloat(iInputText);
        iTextView = new ITextView(ui);
        iCategory = new ICategory(ui);
        iCollapse = new ICollapse(ui);

        root = new FrameLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    if (expanded && editing == null) collapse();
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };

        bubble = new BubbleView(context, ui.bold, Theme.BUBBLE_TEXT, decodeIcon(Icon()));
        bubble.setOnTouchListener(bubbleTouchListener());
        root.addView(bubble, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        panel = buildPanel(context);
        panel.setVisibility(View.GONE);
        root.addView(panel);

        creditText = ui.text("", Theme.TEXT_BODY, Theme.TEXT, ui.regular);
        creditText.setLineSpacing(ui.dp(2), 1f);

        Init(context, titleText, creditText);
    }

    private LinearLayout buildPanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Theme.PANEL_BG);
        background.setCornerRadius(Theme.PANEL_RADIUS);
        background.setStroke((int) Theme.PANEL_STROKE, Theme.PANEL_STROKE);
        panel.setBackground(background);
        panel.setClickable(true);

        panel.addView(buildHeader(context), new LinearLayout.LayoutParams(MATCH_PARENT, (int) ui.dp(Theme.HEADER_HEIGHT + 6)));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        sidebar = new LinearLayout(context);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(0, (int) ui.dp(2), 0, (int) ui.dp(10));
        sidebarScroll = new ScrollView(context);
        sidebarScroll.setVerticalScrollBarEnabled(false);
        sidebarScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sidebarScroll.addView(sidebar, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        body.addView(sidebarScroll, new LinearLayout.LayoutParams((int) ui.dp(85), MATCH_PARENT));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        contentScroll = new ScrollView(context);
        contentScroll.setVerticalScrollBarEnabled(false);
        contentScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        contentScroll.setPadding(0, 0, (int) ui.dp(14), (int) ui.dp(6));
        contentScroll.setClipToPadding(false);
        contentScroll.addView(content, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        body.addView(contentScroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));

        return panel;
    }

    private View buildHeader(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) ui.dp(18), (int) ui.dp(4), (int) ui.dp(18), 0);

        View mark = new MarkView(getContext);
        header.addView(mark, new LinearLayout.LayoutParams((int) ui.dp(7), (int) ui.dp(15)));

        titleText = ui.text("", Theme.TEXT_TITLE, Theme.TEXT, Typeface.create(ui.bold, Typeface.BOLD_ITALIC));
        titleText.setLetterSpacing(0.08f);
        titleText.setSingleLine(true);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        titleParams.leftMargin = (int) ui.dp(8);
        header.addView(titleText, titleParams);

        header.addView(new StatusView(ui));

        header.setOnTouchListener(dragListener());
        return header;
    }

    private Rect usableArea() {
        Rect area = new Rect();
        root.getWindowVisibleDisplayFrame(area);
        if (area.isEmpty()) {
            area.set(0, 0, getContext.getResources().getDisplayMetrics().widthPixels,
                    getContext.getResources().getDisplayMetrics().heightPixels);
        }
        return area;
    }

    private void layoutPanel() {
        Rect area = usableArea();
        int margin = (int) ui.dp(Theme.PANEL_MARGIN);
        int width = Math.min((int) ui.dp(Theme.PANEL_MAX_WIDTH), area.width() - margin * 2);
        int height = Math.min((int) ui.dp(Theme.PANEL_MAX_HEIGHT), area.height() - margin * 2);
        panel.setLayoutParams(new FrameLayout.LayoutParams(width, height));
        sidebarScroll.getLayoutParams().width = Math.round(width * Theme.SIDEBAR_RATIO);
        sidebarScroll.requestLayout();
    }

    @Override
    public void ShowMenu() {
        Preferences.loadPrefBool(SAVE_SETTINGS, SAVE_SETTINGS_ID, false);

        java.util.List<FeatureEntity> features = FeatureParser.parse(GetFeatureList());
        buildTabs(features);
        for (Tab tab : tabs) {
            column(tab, null, tab.items, tab.settings);
            prebuildPages(tab.items);
        }
        if (!tabs.isEmpty()) selectTab(0);
    }

    private void buildTabs(java.util.List<FeatureEntity> features) {
        tabs.clear();
        pageCache.clear();
        sidebar.removeAllViews();

        java.util.List<FeatureEntity> loose = new java.util.ArrayList<>();
        for (FeatureEntity item : features) {
            if ("ITab".equals(item.type)) {
                tabs.add(tab(item.name, item.icon, item.children, false));
            } else {
                loose.add(item);
            }
        }
        if (!loose.isEmpty()) tabs.add(0, tab(GENERAL_TAB, "grid", loose, false));
        tabs.add(tab(SETTINGS_TAB, "gear", null, true));

        for (int i = 0; i < tabs.size(); i++) {
            sidebar.addView(buildTabView(tabs.get(i), i));
        }
    }

    private static Tab tab(String name, String icon, java.util.List<FeatureEntity> items, boolean settings) {
        Tab tab = new Tab();
        tab.name = name;
        tab.icon = TextUtils.isEmpty(icon) ? "grid" : icon;
        tab.items = items;
        tab.settings = settings;
        return tab;
    }

    private View buildTabView(Tab tab, int index) {
        LinearLayout item = new LinearLayout(getContext);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding((int) ui.dp(4), (int) ui.dp(12), (int) ui.dp(4), (int) ui.dp(12));
        item.setClickable(true);

        tab.iconView = ui.icon(tab.icon, 22, Theme.TEXT_DIM);
        item.addView(tab.iconView);

        tab.label = ui.text(Ui.html(tab.name), Theme.TEXT, Theme.TEXT_DIM, ui.regular);
        tab.label.setGravity(Gravity.CENTER);
        tab.label.setMaxLines(2);
        tab.label.setEllipsize(TextUtils.TruncateAt.END);
        tab.label.setLineSpacing(ui.dp(1), 1f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        labelParams.topMargin = (int) ui.dp(6);
        item.addView(tab.label, labelParams);

        item.setOnClickListener(v -> selectTab(index));
        return item;
    }

    private void selectTab(int index) {
        endEdit();
        boolean same = index == activeTab && pages.isEmpty();
        activeTab = index;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            boolean active = i == index;
            tab.iconView.setColor(active ? Theme.ACCENT : Theme.TEXT_DIM);
            tab.label.setTextColor(active ? Theme.TEXT : Theme.TEXT_DIM);
            tab.label.setTypeface(active ? ui.medium : ui.regular);
        }
        if (same) return;
        pages.clear();
        render(0);
    }

    @Override
    public void openPage(String title, java.util.List<FeatureEntity> children) {
        endEdit();
        pages.push(new Page(title, children));
        render(1);
    }

    public void closePage() {
        endEdit();
        if (pages.isEmpty()) return;
        pages.pop();
        render(-1);
    }

    private void render(int direction) {
        Tab tab = tabs.get(activeTab);
        Page page = pages.peek();
        LinearLayout column = page != null
                ? column(page.items, page.title, page.items, false)
                : column(tab, null, tab.items, tab.settings);

        content.removeAllViews();
        content.addView(column, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        contentScroll.scrollTo(0, 0);
        content.setAlpha(0f);
        content.setTranslationX(direction * ui.dp(24));
        content.animate().alpha(1f).translationX(0f).setDuration(direction == 0 ? 140 : 200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private LinearLayout column(Object key, String title, java.util.List<FeatureEntity> items, boolean settings) {
        LinearLayout column = pageCache.get(key);
        if (column != null) return column;

        column = new LinearLayout(getContext);
        column.setOrientation(LinearLayout.VERTICAL);
        if (title != null) column.addView(buildPageHeader(title));
        if (settings) renderSettings(column);
        else renderItems(column, items);
        pageCache.put(key, column);
        return column;
    }

    private void prebuildPages(java.util.List<FeatureEntity> items) {
        if (items == null) return;
        for (FeatureEntity item : items) {
            if ("ICollapse".equals(item.type) && item.children != null) {
                column(item.children, item.name, item.children, false);
                prebuildPages(item.children);
            }
        }
    }

    private void renderItems(LinearLayout column, java.util.List<FeatureEntity> items) {
        if (items == null) return;
        for (FeatureEntity item : items) {
            View view = build(item);
            if (view != null) column.addView(view);
        }
    }

    private View build(FeatureEntity item) {
        ComponentType type;
        try {
            type = ComponentType.valueOf(item.type);
        } catch (IllegalArgumentException | NullPointerException e) {
            Log.e(TAG, "Unknown feature type: " + item.type);
            return null;
        }
        switch (type) {
            case ISwitch: return iSwitch.build(item);
            case ICheckBox: return iCheckBox.build(item);
            case ISlider: return iSlider.build(item);
            case ISpinner: return iSpinner.build(item);
            case IRadioButton: return iRadioButton.build(item);
            case IButton: return iButton.build(item);
            case IButtonLink: return iButtonLink.build(item);
            case IInputText: return iInputText.build(item);
            case IInputInt: return iInputInt.build(item);
            case IInputFloat: return iInputFloat.build(item);
            case ITextView: return iTextView.build(item);
            case ICategory: return iCategory.build(item);
            case ICollapse: return iCollapse.build(item);
            case ITab:
                Log.e(TAG, "ITab is only allowed at the top level: " + item.name);
                return null;
            default: return null;
        }
    }

    private View buildPageHeader(String title) {
        LinearLayout header = new LinearLayout(getContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight((int) ui.dp(36));
        header.setClickable(true);

        IconView back = ui.icon("chevron-left", 18, Theme.TEXT);
        header.addView(back);

        TextView text = ui.text(Ui.html(title), Theme.TEXT_TITLE, Theme.TEXT, ui.medium);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        textParams.leftMargin = (int) ui.dp(6);
        header.addView(text, textParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params.bottomMargin = (int) ui.dp(6);
        header.setLayoutParams(params);
        header.setOnClickListener(v -> closePage());
        return header;
    }

    private void renderSettings(LinearLayout column) {
        FeatureEntity save = new FeatureEntity();
        save.id = SAVE_SETTINGS_ID;
        save.type = "ISwitch";
        save.name = SAVE_SETTINGS;
        save.desc = "Restore your last values the next time the menu starts";
        column.addView(iCategory.build("Menu"));
        column.addView(iSwitch.build(save));

        if (creditText.getText().length() > 0) {
            column.addView(iCategory.build("Credits"));
            LinearLayout card = ui.card(true, false);
            card.setPadding(card.getPaddingLeft(), (int) ui.dp(12), card.getPaddingRight(), (int) ui.dp(12));
            if (creditText.getParent() != null) ((ViewGroup) creditText.getParent()).removeView(creditText);
            card.addView(creditText, ui.weight());
            column.addView(card);
        }

        column.addView(iCategory.build("Application"));
        column.addView(infoRow("Name", String.valueOf(getContext.getApplicationInfo().loadLabel(getContext.getPackageManager()))));
        column.addView(infoRow("Package", getContext.getPackageName()));
        try {
            column.addView(infoRow("Version", getContext.getPackageManager().getPackageInfo(getContext.getPackageName(), 0).versionName));
        } catch (Exception e) {
            Log.e(TAG, "Cannot read app version", e);
        }
    }

    private View infoRow(String label, String value) {
        LinearLayout card = ui.card(true, false);
        card.addView(ui.text(label, Theme.TEXT_BODY, Theme.TEXT, ui.regular));
        TextView valueText = ui.text(value, Theme.TEXT_BODY, Theme.TEXT_DIM, ui.regular);
        valueText.setGravity(Gravity.END);
        valueText.setSingleLine(true);
        valueText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams params = ui.weight();
        params.leftMargin = (int) ui.dp(16);
        card.addView(valueText, params);
        return card;
    }

    private void expand() {
        if (expanded) return;
        expanded = true;
        bubbleX = vmParams.x;
        bubbleY = vmParams.y;

        layoutPanel();
        Rect area = usableArea();
        int margin = (int) ui.dp(Theme.PANEL_MARGIN);
        int width = panel.getLayoutParams().width;
        int height = panel.getLayoutParams().height;
        vmParams.x = clamp(bubbleX, margin, area.width() - width - margin);
        vmParams.y = clamp(bubbleY, margin, area.height() - height - margin);

        bubble.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        updateWindow();

        int bubbleSize = (int) ui.dp(Theme.BUBBLE_SIZE);
        panel.setPivotX(clamp(bubbleX - vmParams.x + bubbleSize / 2, 0, width));
        panel.setPivotY(clamp(bubbleY - vmParams.y + bubbleSize / 2, 0, height));
        panel.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void collapse() {
        if (!expanded) return;
        endEdit();
        expanded = false;
        panel.animate().cancel();
        panel.setVisibility(View.GONE);
        bubble.setVisibility(View.VISIBLE);
        vmParams.x = bubbleX;
        vmParams.y = bubbleY;
        updateWindow();
    }

    private void keepBubbleOnScreen() {
        Rect area = usableArea();
        int size = (int) ui.dp(Theme.BUBBLE_SIZE);
        int x = clamp(vmParams.x, 0, area.width() - size);
        int y = clamp(vmParams.y, 0, area.height() - size);
        if (x == vmParams.x && y == vmParams.y) return;
        vmParams.x = x;
        vmParams.y = y;
        updateWindow();
    }

    private View.OnTouchListener bubbleTouchListener() {
        int slop = ViewConfiguration.get(getContext).getScaledTouchSlop();
        return new View.OnTouchListener() {
            private float touchX, touchY;
            private int startX, startY;
            private boolean moved;

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        startX = vmParams.x;
                        startY = vmParams.y;
                        moved = false;
                        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchX, dy = event.getRawY() - touchY;
                        if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                        if (moved) {
                            vmParams.x = startX + (int) dx;
                            vmParams.y = startY + (int) dy;
                            updateWindow();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                        if (moved) keepBubbleOnScreen();
                        else expand();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                        if (moved) keepBubbleOnScreen();
                        return true;
                }
                return false;
            }
        };
    }

    private View.OnTouchListener dragListener() {
        return new View.OnTouchListener() {
            private float touchX, touchY;
            private int startX, startY;

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        startX = vmParams.x;
                        startY = vmParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        vmParams.x = startX + (int) (event.getRawX() - touchX);
                        vmParams.y = startY + (int) (event.getRawY() - touchY);
                        updateWindow();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        };
    }

    @Override
    public void beginEdit(EditText field) {
        if (editing == field) return;
        endEdit();
        editing = field;
        vmParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        updateWindow();

        field.setFocusableInTouchMode(true);
        field.requestFocus();
        field.setSelection(field.length());
        field.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getContext.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && editing == field) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    @Override
    public void endEdit() {
        if (editing == null) return;
        EditText field = editing;
        editing = null;
        InputMethodManager imm = (InputMethodManager) getContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
        field.setFocusable(false);
        vmParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        updateWindow();
    }

    private native void Init(Context context, TextView title, TextView subTitle);

    private native String Icon();

    private static Bitmap decodeIcon(String base64) {
        if (TextUtils.isEmpty(base64)) return null;
        int comma = base64.indexOf(',');
        if (base64.startsWith("data:") && comma >= 0) base64 = base64.substring(comma + 1);
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) Log.e(TAG, "Bubble icon is not a valid image, using text instead");
            return bitmap;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Bubble icon is not valid base64, using text instead", e);
            return null;
        }
    }

    private static int overlayFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
    }

    @SuppressLint("WrongConstant")
    public void SetWindowManagerWindowService() {
        int mType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        vmParams = new WindowManager.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, mType, overlayFlags(), PixelFormat.TRANSLUCENT);
        vmParams.gravity = Gravity.TOP | Gravity.START;
        vmParams.x = POS_X;
        vmParams.y = POS_Y;

        mWindowManager = (WindowManager) getContext.getSystemService(Context.WINDOW_SERVICE);

        canvasParams = new WindowManager.LayoutParams(
                MATCH_PARENT,
                MATCH_PARENT,
                mType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        canvasParams.gravity = Gravity.TOP | Gravity.START;
        canvasParams.x = 0;
        canvasParams.y = 100;
        if (drawView.getParent() == null) mWindowManager.addView(drawView, canvasParams);
        if (root.getParent() == null) mWindowManager.addView(root, vmParams);
    }

    @SuppressLint("WrongConstant")
    public void SetWindowManagerActivity() {
        vmParams = new WindowManager.LayoutParams(
                WRAP_CONTENT,
                WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                overlayFlags() |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT
        );
        vmParams.gravity = Gravity.TOP | Gravity.START;
        vmParams.x = POS_X;
        vmParams.y = POS_Y;

        mWindowManager = ((android.app.Activity) getContext).getWindowManager();
        mWindowManager.addView(root, vmParams);
    }

    private void updateWindow() {
        if (mWindowManager != null && root.getParent() != null) {
            mWindowManager.updateViewLayout(root, vmParams);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }

    public void setModMenuVisibility(boolean visible) {
        if (root != null) {
            root.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    public void setEspLayoutVisibility(boolean visible) {
        if (drawView != null) {
            drawView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void KillMenu() {
        endEdit();
        if (root != null && mWindowManager != null && root.getParent() != null) {
            mWindowManager.removeView(root);
        }
        if (drawView != null && mWindowManager != null && drawView.getParent() != null) {
            mWindowManager.removeView(drawView);
        }
    }
}
