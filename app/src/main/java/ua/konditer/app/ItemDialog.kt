package ua.konditer.app

import android.app.Activity
import android.content.DialogInterface
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ua.konditer.app.databinding.DialogItemBinding

/** Створення / редагування даних виробу. */
object ItemDialog {

    private val FLAT_CATEGORIES = setOf("Топери", "Печиво з малюнком")

    fun edit(activity: Activity, existing: Item?, onSaved: (Item) -> Unit) {
        val b = DialogItemBinding.inflate(activity.layoutInflater)
        val cats = Catalog.categories()
        b.editCategory.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, cats))
        b.editCategory.setOnClickListener { b.editCategory.showDropDown() }
        b.editCategory.setOnItemClickListener { _, _, pos, _ ->
            b.kindGroup.check(if (cats[pos] in FLAT_CATEGORIES) b.kindFlat.id else b.kindVolume.id)
        }

        if (existing != null) {
            b.editName.setText(existing.name)
            b.editCategory.setText(existing.category, false)
            b.editWeight.setText(existing.weight)
            b.editPrice.setText(existing.price)
            b.editArticle.setText(existing.article)
            b.editDescription.setText(existing.description)
        }
        b.kindGroup.check(if (existing?.kind == Kind.FLAT) b.kindFlat.id else b.kindVolume.id)

        val dlg = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) "Новий виріб" else "Редагування")
            .setView(b.root)
            .setPositiveButton(if (existing == null) "Далі: знімки" else "Зберегти", null)
            .setNegativeButton("Скасувати", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = b.editName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) { b.layName.error = "Введіть назву"; return@setOnClickListener }
                val dup = Catalog.all().any { it.id != existing?.id && it.name.equals(name, ignoreCase = true) }
                if (dup) { b.layName.error = "Виріб з такою назвою вже є"; return@setOnClickListener }

                val target = existing ?: Catalog.create(name)
                Catalog.update(target.id) {
                    this.name = name
                    category = b.editCategory.text?.toString()?.trim().orEmpty()
                    kind = if (b.kindGroup.checkedButtonId == b.kindFlat.id) Kind.FLAT else Kind.VOLUME
                    weight = b.editWeight.text?.toString()?.trim().orEmpty()
                    price = b.editPrice.text?.toString()?.trim().orEmpty()
                    article = b.editArticle.text?.toString()?.trim().orEmpty()
                    description = b.editDescription.text?.toString()?.trim().orEmpty()
                }
                dlg.dismiss()
                Catalog.get(target.id)?.let(onSaved)
            }
        }
        dlg.show()
    }
}

/** Вибір виробу з каталогу з пошуком. */
object ItemPicker {
    fun show(activity: Activity, title: String, onPick: (Item) -> Unit) {
        val all = Catalog.all()
        var shown = all
        val dp = activity.resources.displayMetrics.density

        val search = TextInputEditText(activity).apply { hint = "Пошук" }
        val searchLay = TextInputLayout(activity).apply { addView(search) }
        val list = ListView(activity)
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        fun refill() {
            adapter.clear()
            adapter.addAll(shown.map { if (it.category.isBlank()) it.name else "${it.name}  ·  ${it.category}" })
        }
        list.adapter = adapter
        refill()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(searchLay)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * dp).toInt()))
        }
        val dlg = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Скасувати", null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                shown = if (q.isEmpty()) all else all.filter {
                    it.name.lowercase().contains(q) || it.category.lowercase().contains(q) ||
                        it.article.lowercase().contains(q)
                }
                refill()
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            onPick(shown[pos])
        }
        dlg.show()
    }
}
