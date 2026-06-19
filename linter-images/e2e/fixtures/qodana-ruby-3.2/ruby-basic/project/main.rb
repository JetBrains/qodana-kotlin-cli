# Ruby inspection planted defect for the qodana-ruby e2e.
def compute(value)
  # RubyUnusedLocalVariable: `unused` is assigned and never read.
  unused = value * 2
  value + 1
end

puts compute(41)
