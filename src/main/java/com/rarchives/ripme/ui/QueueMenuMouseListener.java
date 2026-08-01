package com.rarchives.ripme.ui;

import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.DefaultListModel;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

import com.rarchives.ripme.utils.Utils;

class QueueMenuMouseListener extends MouseAdapter {
    private final JPopupMenu popup = new JPopupMenu();
    private JTable queueTable;
    private DefaultListModel<Object> queueListModel;
    private final Consumer<DefaultListModel<Object>> updateQueue;

    public QueueMenuMouseListener(Consumer<DefaultListModel<Object>> updateQueue) {
        this.updateQueue = updateQueue;
        updateUI();
    }

    @SuppressWarnings("serial")
    public void updateUI() {
        popup.removeAll();

        Action moveTop = new AbstractAction(Utils.getLocalizedString("queue.move.top")) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                int[] indices = queueTable.getSelectedRows();
                if (indices.length == 0) {
                    return;
                }
                List<Object> selected = new ArrayList<>();
                for (int index : indices) {
                    selected.add(queueListModel.get(index));
                }
                for (int i = indices.length - 1; i >= 0; i--) {
                    queueListModel.remove(indices[i]);
                }
                for (int i = 0; i < selected.size(); i++) {
                    queueListModel.add(i, selected.get(i));
                }
                int[] newIndices = new int[selected.size()];
                for (int i = 0; i < selected.size(); i++) {
                    newIndices[i] = i;
                }
                setSelectedRows(newIndices);
                updateUI();
            }
        };
        popup.add(moveTop);

        Action moveUp = new AbstractAction(Utils.getLocalizedString("queue.move.up")) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                int[] indices = queueTable.getSelectedRows();
                if (indices.length == 0) {
                    return;
                }
                for (int i = 0; i < indices.length; i++) {
                    int index = indices[i];
                    if (index > 0) {
                        Object element = queueListModel.get(index);
                        queueListModel.remove(index);
                        queueListModel.add(index - 1, element);
                        indices[i] = index - 1;
                    }
                }
                setSelectedRows(indices);
                updateUI();
            }
        };
        popup.add(moveUp);

        Action moveDown = new AbstractAction(Utils.getLocalizedString("queue.move.down")) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                int[] indices = queueTable.getSelectedRows();
                if (indices.length == 0) {
                    return;
                }
                for (int i = indices.length - 1; i >= 0; i--) {
                    int index = indices[i];
                    if (index < queueListModel.getSize() - 1) {
                        Object element = queueListModel.get(index);
                        queueListModel.remove(index);
                        queueListModel.add(index + 1, element);
                        indices[i] = index + 1;
                    }
                }
                setSelectedRows(indices);
                updateUI();
            }
        };
        popup.add(moveDown);

        Action forceSelected = new AbstractAction(Utils.getLocalizedString("queue.force")) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                int[] selected = queueTable.getSelectedRows();
                for (int row : selected) {
                    QueueEntry entry = QueueEntry.from(queueListModel.get(row));
                    if (entry != null) {
                        entry.setForceRip(true);
                        queueListModel.set(row, entry);
                    }
                }
                fireTableDataChanged();
                setSelectedRows(selected);
                updateUI();
            }
        };
        popup.add(forceSelected);

        Action removeSelected = new AbstractAction(Utils.getLocalizedString("queue.remove.selected")) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                int[] indices = queueTable.getSelectedRows();
                for (int i = indices.length - 1; i >= 0; i--) {
                    queueListModel.remove(indices[i]);
                }
                fireTableDataChanged();
                updateUI();
            }
        };
        popup.add(removeSelected);

        Action clearQueue = new AbstractAction(Utils.getLocalizedString("queue.remove.all")) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                if (JOptionPane.showConfirmDialog(null, Utils.getLocalizedString("queue.validation"), "RipMe",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    queueListModel.removeAllElements();
                    fireTableDataChanged();
                    updateUI();
                }
            }
        };
        popup.add(clearQueue);

        updateQueue.accept(queueListModel);
    }

    private void fireTableDataChanged() {
        if (queueTable != null && queueTable.getModel() instanceof AbstractTableModel) {
            ((AbstractTableModel) queueTable.getModel()).fireTableDataChanged();
        }
    }

    private void setSelectedRows(int[] indices) {
        fireTableDataChanged();
        if (queueTable == null) {
            return;
        }
        queueTable.clearSelection();
        for (int index : indices) {
            if (index >= 0 && index < queueTable.getRowCount()) {
                queueTable.addRowSelectionInterval(index, index);
            }
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        checkPopupTrigger(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        checkPopupTrigger(e);
    }

    private void checkPopupTrigger(MouseEvent e) {
        if (!e.isPopupTrigger() || !(e.getSource() instanceof JTable)) {
            return;
        }

        queueTable = (JTable) e.getSource();
        queueListModel = MainWindow.getQueueListModel();
        if (queueListModel == null) {
            return;
        }
        queueTable.requestFocus();

        int nx = e.getX();
        if (nx > 500) {
            nx = nx - popup.getSize().width;
        }
        popup.show(e.getComponent(), nx, e.getY() - popup.getSize().height);
    }
}
