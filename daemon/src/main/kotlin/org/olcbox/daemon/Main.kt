package org.olcbox.daemon

import org.olcbox.daemon.install.InstallCommand

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "install" -> InstallCommand.install()
        "uninstall" -> InstallCommand.uninstall()
        else -> DaemonServer.run()
    }
}
