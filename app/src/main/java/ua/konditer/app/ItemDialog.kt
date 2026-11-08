package ua.konditer.app

import android.app.Activity
import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ua.konditer.app.databinding.DialogItemBinding

/** Діалог введення назви, ваги та інших даних виробу. */
object ItemDialog {
    fun show(
        activity: Activity,
        title: String,
        item: Item?,
        positive: String,
        onSave: (name: String, weight: String, note: String) -> Unit
    ) {
        val b = DialogItemBinding.inflate(activity.layoutInflater)
        item?.let {
            b.editName.setText(it.name)
            b.editWeight.setText(it.weight)
            b.editNote.setText(it.note)
        }
        val dlg = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(b.root)
            .setPositiveButton(positive, null)
            .setNegativeButton("Скасувати", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = b.editName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    b.editName.error = "Введіть назву"
                    return@setOnClickListener
                }
                onSave(
                    name,
                    b.editWeight.text?.toString()?.trim().orEmpty(),
                    b.editNote.text?.toString()?.trim().orEmpty()
                )
                dlg.dismiss()
            }
        }
        dlg.show()
    }
}
