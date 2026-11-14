package com.github.timothydowney.plugins.pipeline.npm.WithNPMStep
def f = namespace(lib.FormTagLib) as lib.FormTagLib

f.entry(field: 'npmrcConfig', title: _('npmrc Config')) {
    f.select()
}

f.entry(field: 'path', title: _('Target Directory')) {
    f.textbox()
}

f.entry(field: 'cleanup', title: _('Clean up .npmrc on exit')) {
    f.checkbox(default: true)
}
