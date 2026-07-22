#!/bin/bash
set -e

# Change to the eval directory
cd "$(dirname "$0")"

echo "Activating virtual environment..."
if [ ! -f "venv/bin/activate" ]; then
    echo "Virtual environment not found! Please run setup first."
    exit 1
fi
source venv/bin/activate

if [ -f ".env" ]; then
    source .env
else
    echo "Warning: .env not found."
fi

echo "---"
echo "Step 1: Generating fixtures (record.py)..."
python scripts/record.py

echo "---"
echo "Step 2: Bootstrapping references (bootstrap_refs.py)..."
python scripts/bootstrap_refs.py

echo "---"
echo "Step 3: Generating scorecard (scorecard.py)..."
python scoring/scorecard.py

echo "---"
echo "Evaluation complete! Check reports/scorecard.md for final metrics."
