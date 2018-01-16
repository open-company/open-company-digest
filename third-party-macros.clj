(disable-warning
 {:linter :unused-ret-vals
  :if-inside-macroexpansion-of #{'defun.core/defun}
  :within-depth 20
  :reason "Using doall to forcefully drain a pmap for side effects, don't care about the ret val."})