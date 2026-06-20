<?php
// qodana-php e2e planted defect: a genuine undefined-class reference. `UndefinedHelperService` is
// never defined or imported, so PhpUndefinedClassInspection — enabled in this image's default Ultimate
// PhpStorm profile — reports it. No scalar type hints (which a low default PHP language level would
// itself flag as undefined classes), so this defect is the single, deterministic finding.

function make_service()
{
    return new UndefinedHelperService();
}

make_service();
