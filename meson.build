project('canon-universal-driver', 'c', version: '0.1.0', default_options: ['warning_level=2'])

cups_dep = dependency('cups')
libusb_dep = dependency('libusb-1.0')

inc = include_directories('src/protocol', 'src/backend', 'src/filter', 'src/util')

protocol_sources = ['src/protocol/g_series.c']
filter_sources = ['src/filter/canon_filter.c', 'src/backend/usb.c'] + protocol_sources

executable('canonfilter', filter_sources,
    dependencies: [cups_dep, libusb_dep],
    include_directories: inc,
    install: true,
    install_dir: '/usr/lib/cups/filter')

custom_target('ppds',
    input: 'models/models.json',
    output: 'ppds_generated',
    command: [find_program('python3'), meson.source_root() / 'ppd/generate_ppd.py', '@INPUT@', meson.source_root() / 'ppd/templates', '@OUTDIR@/ppd_out'],
    build_by_default: true)

install_data('udev/60-canon-universal.rules', install_dir: '/etc/udev/rules.d')
