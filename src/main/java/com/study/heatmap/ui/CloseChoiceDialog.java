package com.study.heatmap.ui;

import com.intellij.openapi.ui.DialogWrapper;
import com.study.heatmap.core.Heat;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;

/**
 * 退出项目时的选择窗口。
 * 「保留」= 落盘但不出报告，下次继续累加；「结算」= 立刻生成热力图。
 */
public class CloseChoiceDialog extends DialogWrapper {

    public enum Choice {
        KEEP, SETTLE, CANCEL
    }

    private final JRadioButton keep = new JRadioButton("保留记录，下次继续累计");
    private final JRadioButton settle = new JRadioButton("结算本次，并生成学习热力图");

    private final double sessionTotal;
    private final double projectTotal;
    private final int files;
    private final int lines;

    public CloseChoiceDialog(double sessionTotal, double projectTotal, int files, int lines) {
        super(false);
        this.sessionTotal = sessionTotal;
        this.projectTotal = projectTotal;
        this.files = files;
        this.lines = lines;
        setTitle("学习记录");
        setOKButtonText("确定");
        setCancelButtonText("取消");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        root.setPreferredSize(new Dimension(440, 200));

        JLabel title = new JLabel("本次学习 " + Heat.duration(sessionTotal));
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 5f));

        JLabel sub = new JLabel("项目累计 " + Heat.duration(projectTotal)
                + " · " + files + " 个文件 / " + lines + " 行");
        sub.setForeground(new Color(0x8B909C));

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.add(title, BorderLayout.NORTH);
        head.add(sub, BorderLayout.CENTER);
        root.add(head, BorderLayout.NORTH);

        keep.setSelected(true);
        ButtonGroup group = new ButtonGroup();
        group.add(keep);
        group.add(settle);

        JLabel hint = new JLabel("「保留」不会生成报告，数据留到下次打开这个项目继续累加。");
        hint.setForeground(new Color(0x8B909C));

        JPanel options = new JPanel(new GridLayout(0, 1, 0, 10));
        options.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        options.add(keep);
        options.add(settle);
        options.add(hint);
        root.add(options, BorderLayout.CENTER);

        return root;
    }

    public Choice showAndGetChoice() {
        if (!showAndGet()) {
            return Choice.CANCEL;
        }
        return settle.isSelected() ? Choice.SETTLE : Choice.KEEP;
    }
}
