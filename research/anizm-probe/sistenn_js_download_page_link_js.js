// vidstack-download-page-link-standalone-v1
;(() => {
    if (!window.MutationObserver) return

    const watchedLayouts = new WeakSet()

    const watchLayout = (layout) => {
        if (watchedLayouts.has(layout)) return
        watchedLayouts.add(layout)

        let repairQueued = false
        const repairDownloadLink = () => {
            repairQueued = false
            const link = layout.querySelector('a.vds-download-button')
            const downloadPage = layout.download
            if (!link || typeof downloadPage !== 'string') return

            if (link.hasAttribute('download'))
                link.removeAttribute('download')
            if (link.getAttribute('href') !== downloadPage)
                link.setAttribute('href', downloadPage)
            if (link.getAttribute('target') !== '_blank')
                link.setAttribute('target', '_blank')
            if (link.getAttribute('rel') !== 'noopener noreferrer')
                link.setAttribute('rel', 'noopener noreferrer')
        }
        const scheduleRepair = () => {
            if (repairQueued) return
            repairQueued = true
            Promise.resolve().then(repairDownloadLink)
        }

        new MutationObserver(scheduleRepair).observe(layout, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['download', 'href'],
        })
        scheduleRepair()
    }

    const scanLayouts = () => {
        document.querySelectorAll('media-video-layout').forEach(watchLayout)
    }

    new MutationObserver(scanLayouts).observe(document.documentElement, {
        childList: true,
        subtree: true,
    })
    scanLayouts()
})()
