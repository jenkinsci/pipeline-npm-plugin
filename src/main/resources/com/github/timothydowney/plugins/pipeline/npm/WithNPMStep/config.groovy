package com.github.timothydowney.plugins.pipeline.npm.WithNPMStep
def f = namespace(lib.FormTagLib) as lib.FormTagLib

f.entry(field: 'npmrcConfig', title: _('npmrc Config')) {
    f.select()
}
